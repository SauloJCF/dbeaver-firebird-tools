package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.PreCheck;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais de Trigger isolada (issue 12): o DML nativo dispara a trigger, e o Interpretador a roda com os
 * mesmos {@code NEW}/{@code OLD} e eventos. Mesmo estado das outras tabelas e mesmos {@code NEW.*} finais, lidos da
 * linha gravada.
 */
class TriggerDifferentialTest {

    private static TestDatabase database;
    private static DifferentialHarness harness;

    @BeforeAll
    static void createTriggers() throws Exception {
        database = TestDatabase.get();
        database.execute(
                "CREATE EXCEPTION TG_ERRO 'erro da trigger'",
                "CREATE TABLE TG_ITEM (ID INTEGER NOT NULL PRIMARY KEY, NOME VARCHAR(20), VALOR NUMERIC(18,2))",
                "CREATE TABLE TG_LOG (TEXTO VARCHAR(200))",
                "CREATE TABLE TG_OUTRA (X INTEGER, \"Misto\" VARCHAR(5))",
                "CREATE TABLE TG_UNI (ID INTEGER, V VARCHAR(10))",
                // As linhas de partida entram antes das triggers, que não as alteram.
                "INSERT INTO TG_ITEM VALUES (1, 'um', 10)",
                "INSERT INTO TG_UNI VALUES (7, 'sete')", """
                CREATE TRIGGER TG_ITEM_BIU FOR TG_ITEM ACTIVE BEFORE INSERT OR UPDATE POSITION 3
                AS
                DECLARE QTD INTEGER = 0;
                DECLARE T VARCHAR(200);
                BEGIN
                  NEW.NOME = UPPER(TRIM(NEW.NOME));
                  IF (NEW.VALOR IS NULL) THEN NEW.VALOR = 0;
                  IF (INSERTING) THEN
                    INSERT INTO TG_LOG VALUES ('insert ' || NEW.ID || ' ' || NEW.NOME);
                  ELSE IF (UPDATING) THEN
                  BEGIN
                    INSERT INTO TG_LOG VALUES (IIF(INSERTING, 'I', 'U') || ' ' || OLD.ID || ': ' || OLD.NOME
                      || ' -> ' || NEW.NOME);
                    IF (NEW.VALOR < OLD.VALOR) THEN EXCEPTION TG_ERRO 'valor não pode diminuir: ' || NEW.VALOR;
                  END
                  FOR SELECT TEXTO FROM TG_LOG WHERE TEXTO CONTAINING NEW.NOME INTO :T DO
                    QTD = QTD + 1;
                  NEW.VALOR = NEW.VALOR + QTD / 100.0;
                  SELECT LEFT(NEW.NOME || '-' || :QTD, 20) FROM RDB$DATABASE INTO NEW.NOME;
                END""", """
                CREATE TRIGGER TG_ITEM_AD FOR TG_ITEM AFTER DELETE
                AS
                BEGIN
                  INSERT INTO TG_LOG VALUES ('delete ' || OLD.ID || ' ' || COALESCE(OLD.NOME, '?') || ' '
                    || IIF(DELETING, 'sim', 'não')); -- só de DELETE: NEW nem compila
                END""", """
                CREATE TRIGGER TG_OUTRA_BI ACTIVE BEFORE INSERT ON TG_OUTRA POSITION 5
                AS
                BEGIN
                  NEW.X = NEW.X * 2;
                  NEW."Misto" = LOWER(NEW."Misto");
                END""", """
                CREATE TRIGGER TG_UNI_BIUD FOR TG_UNI BEFORE INSERT OR UPDATE OR DELETE
                AS
                BEGIN
                  INSERT INTO TG_LOG VALUES (
                    TRIM(IIF(INSERTING, 'I', '') || IIF(UPDATING, 'U', '') || IIF(DELETING, 'D', ''))
                    || ' ' || COALESCE(OLD.ID || '/' || OLD.V, '-') || ' ' || COALESCE(NEW.ID || '/' || NEW.V, '-'));
                  IF (NOT DELETING) THEN NEW.V = COALESCE(NEW.V, 'vazio');
                END""",
                // Triggers de banco e de DDL inativas: só para o Pré-check recusar, sem disparar nos outros testes.
                "CREATE TRIGGER TG_CONEXAO INACTIVE ON CONNECT AS BEGIN END",
                "CREATE TRIGGER TG_DDL INACTIVE BEFORE CREATE TABLE OR ALTER TABLE AS BEGIN END");
        harness = new DifferentialHarness(database).tables("TG_LOG");
    }

    @Test
    void beforeInsertChangesNewAndWritesToAnotherTable() throws SQLException {
        harness.assertSameTrigger("TG_ITEM_BIU", "INSERT INTO TG_ITEM (ID, NOME, VALOR) VALUES (2, '  dois ', NULL)",
                "SELECT * FROM TG_ITEM WHERE ID = 2", Map.of("NEW.ID", 2, "NEW.NOME", "  dois ", "INSERTING", true));
    }

    @Test
    void beforeUpdateReadsOldAndCanRaise() throws SQLException {
        harness.assertSameTrigger("TG_ITEM_BIU", "UPDATE TG_ITEM SET NOME = 'novo', VALOR = 20 WHERE ID = 1",
                "SELECT * FROM TG_ITEM WHERE ID = 1", Map.of("NEW.ID", 1, "NEW.NOME", "novo",
                        "NEW.VALOR", new BigDecimal("20"), "OLD.ID", 1, "OLD.NOME", "um",
                        "OLD.VALOR", new BigDecimal("10"), "UPDATING", true));
        harness.assertSameTrigger("TG_ITEM_BIU", "UPDATE TG_ITEM SET VALOR = 5 WHERE ID = 1",
                "SELECT * FROM TG_ITEM WHERE ID = 1", Map.of("NEW.ID", 1, "NEW.NOME", "um",
                        "NEW.VALOR", new BigDecimal("5"), "OLD.ID", 1, "OLD.NOME", "um",
                        "OLD.VALOR", new BigDecimal("10"), "UPDATING", true));
    }

    @Test
    void afterDeleteReadsOld() throws SQLException {
        harness.assertSameTrigger("TG_ITEM_AD", "DELETE FROM TG_ITEM WHERE ID = 1", null,
                Map.of("OLD.ID", 1, "OLD.NOME", "um", "OLD.VALOR", new BigDecimal("10"), "DELETING", true));
    }

    @Test
    void sqlStandardHeaderAndQuotedColumn() throws SQLException {
        harness.assertSameTrigger("TG_OUTRA_BI", "INSERT INTO TG_OUTRA VALUES (21, 'AbC')",
                "SELECT * FROM TG_OUTRA", Map.of("NEW.X", 21, "NEW.Misto", "AbC", "INSERTING", true));
    }

    @Test
    void universalTriggerWithEachEvent() throws SQLException {
        harness.assertSameTrigger("TG_UNI_BIUD", "INSERT INTO TG_UNI (ID) VALUES (8)",
                "SELECT * FROM TG_UNI WHERE ID = 8", Map.of("NEW.ID", 8, "INSERTING", true));
        harness.assertSameTrigger("TG_UNI_BIUD", "UPDATE TG_UNI SET V = NULL WHERE ID = 7",
                "SELECT * FROM TG_UNI WHERE ID = 7",
                Map.of("NEW.ID", 7, "OLD.ID", 7, "OLD.V", "sete", "UPDATING", true));
        harness.assertSameTrigger("TG_UNI_BIUD", "DELETE FROM TG_UNI WHERE ID = 7", null,
                Map.of("OLD.ID", 7, "OLD.V", "sete", "DELETING", true));
    }

    @Test
    void sourceHasTheHeaderTheDbeaverEditorShows() throws SQLException {
        try (Connection connection = database.connect()) {
            String text = RoutineSourceLoader.loadTrigger(connection, "TG_ITEM_BIU").text();
            List<String> lines = text.lines().toList();
            assertEquals("CREATE TRIGGER \"TG_ITEM_BIU\" FOR \"TG_ITEM\" BEFORE INSERT OR UPDATE", lines.get(0));
            assertEquals("AS", lines.get(1)); // RDB$TRIGGER_SOURCE começa no AS
            assertEquals("CREATE TRIGGER \"TG_UNI_BIUD\" FOR \"TG_UNI\" BEFORE INSERT OR UPDATE OR DELETE",
                    RoutineSourceLoader.loadTrigger(connection, "TG_UNI_BIUD").text().lines().findFirst().get());
            assertEquals("CREATE TRIGGER \"TG_ITEM_AD\" FOR \"TG_ITEM\" AFTER DELETE",
                    RoutineSourceLoader.loadTrigger(connection, "TG_ITEM_AD").text().lines().findFirst().get());
            assertEquals(List.of(new RoutineParser.Column("ID", "INTEGER"), new RoutineParser.Column("NOME",
                    "VARCHAR(20) CHARACTER SET UTF8"), new RoutineParser.Column("VALOR", "NUMERIC(18,2)")),
                    RoutineSourceLoader.loadTrigger(connection, "TG_ITEM_BIU").columns());
        }
    }

    @Test
    void databaseAndDdlTriggersAreRefusedByThePreCheck() throws SQLException {
        try (Connection connection = database.connect()) {
            RoutineSourceLoader.TriggerSource connect = RoutineSourceLoader.loadTrigger(connection, "TG_CONEXAO");
            RoutineSourceLoader.TriggerSource ddl = RoutineSourceLoader.loadTrigger(connection, "TG_DDL");

            assertEquals(List.of(new PreCheck.Finding(new SourcePosition(1, 29),
                    UnsupportedConstruct.DATABASE_TRIGGER.syntax() + " não é suportado pelo debugger")),
                    PreCheck.check(connect.text()));
            assertEquals(List.of(new PreCheck.Finding(new SourcePosition(1, 25),
                    UnsupportedConstruct.DDL_TRIGGER.syntax() + " não é suportado pelo debugger")),
                    PreCheck.check(ddl.text()));
        }
    }
}
