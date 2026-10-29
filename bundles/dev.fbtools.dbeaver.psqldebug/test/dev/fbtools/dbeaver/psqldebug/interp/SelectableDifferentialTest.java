package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais de procedure selecionável (issue 06): {@code SELECT * FROM sp(...)} nativo × linhas emitidas
 * pelo {@code SUSPEND} no Interpretador, na ordem e com os valores, mais o {@code EXECUTE PROCEDURE} da mesma
 * Rotina. As Rotinas deixam uma trilha numa tabela de log, para comparar o que foi feito (e desfeito) entre uma
 * linha e outra.
 */
class SelectableDifferentialTest {

    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        TestDatabase database = TestDatabase.get();
        database.execute("""
                CREATE TABLE SEL_ITEM (ID INTEGER NOT NULL PRIMARY KEY, NOME VARCHAR(20), VALOR NUMERIC(18,2))""",
                "CREATE TABLE SEL_LOG (TEXTO VARCHAR(100))",
                "CREATE EXCEPTION SEL_ERRO 'falhou em @1'",
                "INSERT INTO SEL_ITEM VALUES (1, 'um', 10.25)",
                "INSERT INTO SEL_ITEM VALUES (2, 'dois', NULL)",
                "INSERT INTO SEL_ITEM VALUES (3, 'três', 30.5)", """
                CREATE OR ALTER PROCEDURE SEL_LACO (N INTEGER)
                RETURNS (I INTEGER, Q VARCHAR(10), V NUMERIC(18,2), RC INTEGER)
                AS
                BEGIN
                  I = 0;
                  WHILE (I < N) DO
                  BEGIN
                    I = I + 1;
                    Q = 'r' || I;
                    V = I / 3.0;
                    INSERT INTO SEL_LOG VALUES ('antes ' || :I);
                    RC = ROW_COUNT;
                    SUSPEND;
                    -- O SUSPEND não muda o ROW_COUNT.
                    RC = RC * 10 + ROW_COUNT;
                    INSERT INTO SEL_LOG VALUES ('depois ' || :I || ' ' || :RC);
                  END
                  -- O fim da selecionável não emite linha; no EXECUTE PROCEDURE sem SUSPEND, estes são as saídas.
                  I = 99;
                  Q = NULL;
                END""", """
                CREATE OR ALTER PROCEDURE SEL_FOR (LIMITE INTEGER)
                RETURNS (ID INTEGER, NOME VARCHAR(20), TOTAL NUMERIC(18,2), RC INTEGER)
                AS
                DECLARE V NUMERIC(18,2);
                BEGIN
                  TOTAL = 0;
                  FOR SELECT ID, NOME, VALOR FROM SEL_ITEM WHERE ID <= :LIMITE ORDER BY ID INTO :ID, :NOME, :V DO
                  BEGIN
                    TOTAL = TOTAL + COALESCE(V, 0);
                    RC = ROW_COUNT;
                    SUSPEND;
                    UPDATE SEL_ITEM SET VALOR = COALESCE(VALOR, 0) + 1 WHERE ID = :ID;
                    INSERT INTO SEL_LOG VALUES (:NOME || ' ' || :TOTAL);
                  END
                  ID = NULL;
                  NOME = 'fim';
                  SUSPEND;
                END""", """
                CREATE OR ALTER PROCEDURE SEL_EXIT (PARAR INTEGER)
                RETURNS (I INTEGER)
                AS
                BEGIN
                  I = 1;
                  SUSPEND;
                  IF (PARAR = 1) THEN EXIT;
                  FOR SELECT ID FROM SEL_ITEM ORDER BY ID INTO :I DO
                  BEGIN
                    IF (I = 3) THEN EXIT;
                    SUSPEND;
                  END
                  I = 100;
                  SUSPEND;
                END""", """
                CREATE OR ALTER PROCEDURE SEL_SEM_LINHA (A INTEGER)
                RETURNS (I INTEGER)
                AS
                BEGIN
                  I = A;
                  IF (A > 0) THEN SUSPEND;
                END""", """
                CREATE OR ALTER PROCEDURE SEL_ERRO_NAO_TRATADO (FALHA INTEGER)
                RETURNS (I INTEGER)
                AS
                BEGIN
                  I = 0;
                  WHILE (I < 5) DO
                  BEGIN
                    I = I + 1;
                    INSERT INTO SEL_LOG VALUES ('linha ' || :I);
                    IF (I = FALHA) THEN EXCEPTION SEL_ERRO USING (:I);
                    SUSPEND;
                    INSERT INTO SEL_LOG VALUES ('depois ' || :I);
                    IF (I = -FALHA) THEN I = 1 / 0;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE SEL_ERRO_TRATADO
                RETURNS (I INTEGER, M VARCHAR(30))
                AS
                BEGIN
                  INSERT INTO SEL_LOG VALUES ('a');
                  BEGIN
                    INSERT INTO SEL_LOG VALUES ('b');
                    I = 1;
                    SUSPEND;
                    -- Só o que vem depois do SUSPEND é desfeito quando o handler de fora trata o erro.
                    INSERT INTO SEL_LOG VALUES ('c');
                    I = 1 / 0;
                  END
                  WHEN ANY DO
                  BEGIN
                    INSERT INTO SEL_LOG VALUES ('handler ' || GDSCODE);
                    I = 2;
                    M = 'tratado';
                    SUSPEND;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE SEL_FOR_SAVEPOINT (CASO INTEGER)
                RETURNS (I INTEGER)
                AS
                BEGIN
                  BEGIN
                    INSERT INTO SEL_LOG VALUES ('antes do FOR');
                    -- Depois do SUSPEND, o bloco conta como sem alterações: o FOR não tem savepoint próprio e a
                    -- iteração terminada fica. Sem o SUSPEND, o FOR inteiro é desfeito.
                    IF (CASO = 1) THEN SUSPEND;
                    FOR SELECT ID FROM SEL_ITEM ORDER BY ID INTO :I DO
                    BEGIN
                      INSERT INTO SEL_LOG VALUES ('iteração ' || :I);
                      IF (CASO = 2) THEN SUSPEND;
                      INSERT INTO SEL_LOG VALUES ('fim da iteração ' || :I);
                      IF (I = 2) THEN I = 1 / 0;
                    END
                    WHEN ANY DO INSERT INTO SEL_LOG VALUES ('handler');
                  END
                  I = -1;
                  SUSPEND;
                END""", """
                CREATE OR ALTER PROCEDURE SEL_NOT_NULL (CASO INTEGER)
                RETURNS (A INTEGER NOT NULL, B INTEGER NOT NULL, M VARCHAR(30))
                AS
                BEGIN
                  IF (CASO = 1) THEN
                  BEGIN
                    A = 7;
                    SUSPEND;
                  END
                  IF (CASO = 2) THEN
                  BEGIN
                    -- O erro de validação do SUSPEND pode ser tratado.
                    SUSPEND;
                    WHEN ANY DO
                    BEGIN
                      M = 'pego ' || GDSCODE;
                      A = 1;
                      B = 2;
                      SUSPEND;
                    END
                  END
                  IF (CASO = 3) THEN
                  BEGIN
                    -- O do fim da executável não.
                    EXIT;
                    WHEN ANY DO M = 'nunca';
                  END
                  IF (CASO = 4) THEN
                  BEGIN
                    A = 1;
                    B = 2;
                    SUSPEND;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE SEL_CURSOR
                RETURNS (SUSPEND INTEGER, NOME VARCHAR(20))
                AS
                DECLARE C SCROLL CURSOR FOR (SELECT ID, NOME FROM SEL_ITEM ORDER BY ID);
                BEGIN
                  OPEN C;
                  FETCH LAST FROM C INTO :SUSPEND, :NOME;
                  WHILE (ROW_COUNT = 1) DO
                  BEGIN
                    SUSPEND;
                    FETCH PRIOR FROM C INTO :SUSPEND, :NOME;
                  END
                END""");
        harness = new DifferentialHarness(database).tables("SEL_ITEM", "SEL_LOG");
    }

    @Test
    void rowsComeOutInOrderWithTheOutputsAtEachSuspend() throws SQLException {
        for (int n = 0; n <= 3; n++) {
            harness.assertSameRows("SEL_LACO", n);
        }
    }

    @Test
    void executeProcedureStopsAtTheFirstSuspend() throws SQLException {
        harness.assertSameOutputs("SEL_LACO", 3);
        harness.assertSameOutputs("SEL_LACO", 0); // sem SUSPEND: as saídas do fim
        harness.assertSameOutputs("SEL_FOR", 2);
        harness.assertSameOutputs("SEL_EXIT", 0);
    }

    @Test
    void suspendInsideForSelectWithDmlAtEachRow() throws SQLException {
        harness.assertSameRows("SEL_FOR", 3);
        harness.assertSameRows("SEL_FOR", 0);
    }

    @Test
    void exitAfterSuspend() throws SQLException {
        harness.assertSameRows("SEL_EXIT", 1);
        harness.assertSameRows("SEL_EXIT", 0);
    }

    @Test
    void theEndOfASelectableEmitsNoRow() throws SQLException {
        harness.assertSameRows("SEL_SEM_LINHA", 0);
        harness.assertSameRows("SEL_SEM_LINHA", 5);
    }

    @Test
    void unhandledErrorKeepsTheRowsAlreadyEmittedAndWhatWasDoneBeforeTheLastSuspend() throws SQLException {
        harness.assertSameRows("SEL_ERRO_NAO_TRATADO", 1); // antes da primeira linha
        harness.assertSameRows("SEL_ERRO_NAO_TRATADO", 4);
        harness.assertSameRows("SEL_ERRO_NAO_TRATADO", -3); // depois do SUSPEND, no meio da busca seguinte
        harness.assertSameOutputs("SEL_ERRO_NAO_TRATADO", 1);
        harness.assertSameOutputs("SEL_ERRO_NAO_TRATADO", 4);
    }

    @Test
    void handledErrorDoesNotUndoWhatCameBeforeTheSuspend() throws SQLException {
        harness.assertSameRows("SEL_ERRO_TRATADO");
        harness.assertSameOutputs("SEL_ERRO_TRATADO");
    }

    @Test
    void suspendEmptiesTheSavepointsOfTheOpenBlocks() throws SQLException {
        for (int caso = 0; caso <= 2; caso++) {
            harness.assertSameRows("SEL_FOR_SAVEPOINT", caso);
        }
    }

    @Test
    void nullInNotNullOutput() throws SQLException {
        for (int caso = 0; caso <= 4; caso++) {
            harness.assertSameRows("SEL_NOT_NULL", caso);
            harness.assertSameOutputs("SEL_NOT_NULL", caso);
        }
    }

    @Test
    void suspendFromAScrollCursorWithAnOutputNamedSuspend() throws SQLException {
        harness.assertSameRows("SEL_CURSOR");
    }
}
