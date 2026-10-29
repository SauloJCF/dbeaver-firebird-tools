package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais de {@code FOR SELECT}, {@code FOR EXECUTE STATEMENT} e cursores declarados (issue 05). Os
 * laços deixam uma trilha numa saída texto e numa tabela de log, para que a ordem das linhas, o {@code ROW_COUNT} a
 * cada passo e o DML feito em cada iteração também sejam comparados.
 */
class CursorDifferentialTest {

    private static TestDatabase database;
    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute("""
                CREATE TABLE CUR_ITEM (ID INTEGER NOT NULL PRIMARY KEY, NOME VARCHAR(20), VALOR NUMERIC(18,2),
                                       ATIVO BOOLEAN, CODIGO CHAR(4))""",
                "CREATE TABLE CUR_LOG (N INTEGER, TEXTO VARCHAR(200))",
                "INSERT INTO CUR_ITEM VALUES (1, 'um', 10.25, TRUE, 'A1')",
                "INSERT INTO CUR_ITEM VALUES (2, 'dois', NULL, NULL, NULL)",
                "INSERT INTO CUR_ITEM VALUES (3, 'três', 30.5, FALSE, 'C')",
                "INSERT INTO CUR_ITEM VALUES (4, 'quatro', 4, TRUE, 'D4')", """
                CREATE OR ALTER PROCEDURE CUR_FOR_DML (LIMITE INTEGER)
                RETURNS (TOTAL NUMERIC(18,2), QTD INTEGER, TRILHA VARCHAR(200), RC_DEPOIS INTEGER)
                AS
                DECLARE I INTEGER;
                DECLARE N VARCHAR(20);
                DECLARE V NUMERIC(18,1);
                BEGIN
                  TOTAL = 0;
                  QTD = 0;
                  TRILHA = '';
                  FOR SELECT ID, NOME, VALOR FROM CUR_ITEM WHERE ID <= :LIMITE ORDER BY ID INTO :I, :N, V DO
                  BEGIN
                    INSERT INTO CUR_LOG (N, TEXTO) VALUES (:I, :N || ' ' || COALESCE(:V, -1));
                    TOTAL = TOTAL + COALESCE(V, 0);
                    QTD = QTD + 1;
                    TRILHA = TRILHA || N || ';';
                  END
                  RC_DEPOIS = ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE CUR_ROW_COUNT
                RETURNS (TRILHA VARCHAR(300))
                AS
                DECLARE X INTEGER;
                DECLARE Y INTEGER;
                BEGIN
                  TRILHA = '';
                  UPDATE CUR_ITEM SET NOME = NOME WHERE ID < 0;
                  FOR SELECT ID FROM CUR_ITEM ORDER BY ID INTO :X DO
                  BEGIN
                    TRILHA = TRILHA || 'f' || X || '=' || ROW_COUNT;
                    IF (X = 2) THEN UPDATE CUR_ITEM SET NOME = NOME WHERE ID <= 3;
                    IF (X = 3) THEN SELECT ID FROM CUR_ITEM WHERE ID = 1 INTO :Y;
                    TRILHA = TRILHA || '/' || ROW_COUNT || ' ';
                  END
                  TRILHA = TRILHA || 'fim=' || ROW_COUNT || ' ';
                  FOR SELECT ID FROM CUR_ITEM WHERE ID <= 2 ORDER BY ID INTO :X DO
                  BEGIN
                    TRILHA = TRILHA || 'o' || X || '=' || ROW_COUNT;
                    FOR SELECT ID FROM CUR_ITEM WHERE ID <= 3 INTO :Y DO
                      Y = Y;
                    TRILHA = TRILHA || '/' || ROW_COUNT || ' ';
                  END
                  UPDATE CUR_ITEM SET NOME = NOME WHERE ID = 1;
                  FOR EXECUTE STATEMENT 'SELECT ID FROM CUR_ITEM ORDER BY ID' INTO :X DO
                    TRILHA = TRILHA || 'e' || X || '=' || ROW_COUNT;
                  FOR SELECT ID FROM CUR_ITEM WHERE ID < 0 INTO :X DO
                    X = X;
                  TRILHA = TRILHA || ' vazio=' || ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE CUR_DESVIOS (PARAR INTEGER)
                RETURNS (TRILHA VARCHAR(300), ULTIMO INTEGER)
                AS
                DECLARE X INTEGER;
                DECLARE Y INTEGER;
                BEGIN
                  TRILHA = '';
                  FOR SELECT ID FROM CUR_ITEM ORDER BY ID INTO :X DO
                  BEGIN
                    IF (X = 2) THEN CONTINUE;
                    TRILHA = TRILHA || X;
                    IF (X = 3) THEN LEAVE;
                  END
                  TRILHA = TRILHA || ' ';
                  FORA: FOR SELECT ID FROM CUR_ITEM ORDER BY ID INTO :X DO
                  BEGIN
                    DENTRO: FOR SELECT ID FROM CUR_ITEM ORDER BY ID DESC INTO :Y DO
                    BEGIN
                      IF (Y = 3) THEN CONTINUE DENTRO;
                      IF (Y = X) THEN CONTINUE FORA;
                      IF (X + Y = 7) THEN LEAVE FORA;
                      TRILHA = TRILHA || X || Y || ',';
                    END
                  END
                  TRILHA = TRILHA || ' ';
                  -- Inserir na tabela lida não muda as linhas de um cursor já aberto.
                  FOR SELECT ID FROM CUR_ITEM ORDER BY ID INTO :X DO
                  BEGIN
                    INSERT INTO CUR_ITEM (ID, NOME) VALUES (:X + 100, 'novo');
                    TRILHA = TRILHA || X || '+';
                  END
                  FOR EXECUTE STATEMENT ('SELECT ID FROM CUR_ITEM WHERE ID > ? ORDER BY ID') (100) INTO :X DO
                  BEGIN
                    ULTIMO = X;
                    IF (X = 100 + PARAR) THEN EXIT;
                  END
                  TRILHA = TRILHA || ' sem exit';
                END""", """
                CREATE OR ALTER PROCEDURE CUR_CAMPOS
                RETURNS (TRILHA VARCHAR(300), SOMA NUMERIC(18,2))
                AS
                BEGIN
                  TRILHA = '';
                  SOMA = 0;
                  FOR SELECT ID, NOME, VALOR, ATIVO, CODIGO, VALOR * 2 AS DOBRO FROM CUR_ITEM ORDER BY ID AS CURSOR C DO
                  BEGIN
                    SOMA = SOMA + COALESCE(C.VALOR, 0) + COALESCE(:C.DOBRO, 0);
                    TRILHA = TRILHA || C.ID || ':' || COALESCE(C.CODIGO || '|', 'nulo') || ':';
                    IF (C.ATIVO) THEN TRILHA = TRILHA || 'ativo';
                    ELSE IF (C.ATIVO IS NULL) THEN TRILHA = TRILHA || '?';
                    IF (C.VALOR IS NULL) THEN TRILHA = TRILHA || 'sem valor';
                    INSERT INTO CUR_LOG (N, TEXTO) SELECT C.ID * 10, :C.NOME || '-' || C.NOME
                      FROM CUR_ITEM C WHERE C.ID = :C.ID + 1;
                    TRILHA = TRILHA || ' ';
                  END
                END""", """
                CREATE OR ALTER PROCEDURE CUR_EXPLICITO (MINIMO INTEGER)
                RETURNS (TRILHA VARCHAR(300), X INTEGER, NOME VARCHAR(20))
                AS
                DECLARE C CURSOR FOR (SELECT ID, NOME FROM CUR_ITEM WHERE ID >= :MINIMO ORDER BY ID);
                BEGIN
                  TRILHA = '';
                  X = -5;
                  UPDATE CUR_ITEM SET NOME = NOME;
                  OPEN C;
                  TRILHA = TRILHA || 'open=' || ROW_COUNT || ' ';
                  WHILE (1 = 1) DO
                  BEGIN
                    FETCH C INTO :X, :NOME;
                    TRILHA = TRILHA || X || '=' || ROW_COUNT || ' ';
                    IF (ROW_COUNT = 0) THEN LEAVE;
                  END
                  FETCH C INTO :X, :NOME;
                  TRILHA = TRILHA || 'depois=' || X || '/' || ROW_COUNT || ' ';
                  CLOSE C;
                  CLOSE C;
                  MINIMO = 1; -- com o cursor fechado: vale no próximo OPEN
                  OPEN C;
                  FETCH C;
                  TRILHA = TRILHA || 'campo=' || C.ID || ' ';
                  OPEN C;
                  FETCH C;
                  FETCH C INTO :X, :NOME;
                  TRILHA = TRILHA || 'reaberto=' || X;
                END""", """
                CREATE OR ALTER PROCEDURE CUR_SCROLL
                RETURNS (TRILHA VARCHAR(500))
                AS
                DECLARE X INTEGER;
                DECLARE N INTEGER;
                DECLARE C SCROLL CURSOR FOR (SELECT ID FROM CUR_ITEM WHERE ID <= 3 ORDER BY ID);
                DECLARE V SCROLL CURSOR FOR (SELECT ID FROM CUR_ITEM WHERE ID < 0);
                BEGIN
                  TRILHA = '';
                  OPEN C;
                  X = NULL; FETCH PRIOR FROM C INTO :X; TRILHA = TRILHA || 'p' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH NEXT FROM C INTO :X; TRILHA = TRILHA || 'n' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH LAST FROM C INTO :X; TRILHA = TRILHA || 'l' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH NEXT FROM C INTO :X; TRILHA = TRILHA || 'n' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH NEXT FROM C INTO :X; TRILHA = TRILHA || 'n' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH PRIOR FROM C INTO :X; TRILHA = TRILHA || 'p' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH ABSOLUTE 2 FROM C INTO :X; TRILHA = TRILHA || 'a2:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH ABSOLUTE -1 FROM C INTO :X; TRILHA = TRILHA || 'a-1:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH ABSOLUTE 0 FROM C INTO :X; TRILHA = TRILHA || 'a0:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH RELATIVE 2 FROM C INTO :X; TRILHA = TRILHA || 'r2:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH RELATIVE 0 FROM C INTO :X; TRILHA = TRILHA || 'r0:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH RELATIVE 5 FROM C INTO :X; TRILHA = TRILHA || 'r5:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH RELATIVE -1 FROM C INTO :X; TRILHA = TRILHA || 'r-1:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH ABSOLUTE 9 FROM C INTO :X; TRILHA = TRILHA || 'a9:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH ABSOLUTE -9 FROM C INTO :X; TRILHA = TRILHA || 'a-9:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH RELATIVE 1 FROM C INTO :X; TRILHA = TRILHA || 'r1:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH RELATIVE -5 FROM C INTO :X; TRILHA = TRILHA || 'r-5:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  N = NULL;
                  X = NULL; FETCH ABSOLUTE N FROM C INTO :X; TRILHA = TRILHA || 'aN:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH FIRST FROM C INTO :X; TRILHA = TRILHA || 'f' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  N = 1;
                  X = NULL; FETCH ABSOLUTE N + 2 FROM C INTO :X; TRILHA = TRILHA || 'aN+2:' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH RELATIVE -N FROM C; TRILHA = TRILHA || 'campo=' || C.ID || ROW_COUNT || ' ';
                  CLOSE C;
                  OPEN V;
                  X = NULL; FETCH FIRST FROM V INTO :X; TRILHA = TRILHA || 'vf' || COALESCE(X, '-') || ROW_COUNT || ' ';
                  X = NULL; FETCH LAST FROM V INTO :X; TRILHA = TRILHA || 'vl' || COALESCE(X, '-') || ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE CUR_FETCH_FECHADO
                RETURNS (X INTEGER)
                AS
                DECLARE C CURSOR FOR (SELECT ID FROM CUR_ITEM);
                BEGIN
                  INSERT INTO CUR_LOG (TEXTO) VALUES ('antes do erro');
                  FETCH C INTO :X;
                END""", """
                CREATE OR ALTER PROCEDURE CUR_CAMPO_SEM_LINHA (DEPOIS_DO_FIM BOOLEAN)
                RETURNS (X INTEGER)
                AS
                DECLARE C CURSOR FOR (SELECT ID FROM CUR_ITEM WHERE ID = 1);
                BEGIN
                  OPEN C;
                  IF (DEPOIS_DO_FIM) THEN
                  BEGIN
                    FETCH C;
                    FETCH C;
                  END
                  X = C.ID;
                END""", """
                CREATE OR ALTER PROCEDURE CUR_CAMPO_FECHADO
                RETURNS (X INTEGER)
                AS
                DECLARE C CURSOR FOR (SELECT ID FROM CUR_ITEM);
                BEGIN
                  OPEN C;
                  FETCH C;
                  CLOSE C;
                  X = C.ID;
                END""", """
                CREATE OR ALTER PROCEDURE CUR_MUDA_VARIAVEL (MINIMO INTEGER)
                RETURNS (X INTEGER)
                AS
                DECLARE C CURSOR FOR (SELECT ID FROM CUR_ITEM WHERE ID >= :MINIMO ORDER BY ID);
                BEGIN
                  OPEN C;
                  MINIMO = 1;
                  FETCH C INTO :X;
                END""", """
                CREATE OR ALTER PROCEDURE CUR_ABERTOS (FALHAR BOOLEAN)
                RETURNS (X INTEGER)
                AS
                DECLARE C CURSOR FOR (SELECT ID FROM CUR_ITEM);
                DECLARE S SCROLL CURSOR FOR (SELECT ID FROM CUR_ITEM);
                BEGIN
                  OPEN C;
                  FETCH C INTO :X;
                  OPEN S;
                  FOR SELECT ID FROM CUR_ITEM INTO :X DO
                    LEAVE;
                  FOR SELECT ID FROM CUR_ITEM INTO :X AS CURSOR F DO
                    IF (FALHAR) THEN X = 1 / 0;
                  FOR EXECUTE STATEMENT 'SELECT ID FROM CUR_ITEM' INTO :X DO
                    EXIT;
                END""");
        harness = new DifferentialHarness(database).tables("CUR_ITEM", "CUR_LOG");
    }

    @Test
    void forSelectWithDmlInAnotherTableEachRow() throws Exception {
        harness.assertSameOutputs("CUR_FOR_DML", 3);
        harness.assertSameOutputs("CUR_FOR_DML", 0);
    }

    @Test
    void rowCountFollowsFetchesAndDmlInsideLoops() throws Exception {
        harness.assertSameOutputs("CUR_ROW_COUNT");
    }

    @Test
    void leaveContinueAndExitInsideCursorLoops() throws Exception {
        harness.assertSameOutputs("CUR_DESVIOS", 2);
        harness.assertSameOutputs("CUR_DESVIOS", 99);
    }

    @Test
    void cursorFieldsInExpressionsConditionsAndDml() throws Exception {
        harness.assertSameOutputs("CUR_CAMPOS");
    }

    @Test
    void declaredCursorOpenFetchCloseAndReopen() throws Exception {
        harness.assertSameOutputs("CUR_EXPLICITO", 2);
        harness.assertSameOutputs("CUR_EXPLICITO", 9);
    }

    @Test
    void scrollCursorPositionsLikeTheEngine() throws Exception {
        harness.assertSameOutputs("CUR_SCROLL");
    }

    @Test
    void cursorErrorsAreTheEngineErrors() throws Exception {
        harness.assertSameOutputs("CUR_FETCH_FECHADO");
        harness.assertSameOutputs("CUR_CAMPO_SEM_LINHA", false);
        harness.assertSameOutputs("CUR_CAMPO_SEM_LINHA", true);
        harness.assertSameOutputs("CUR_CAMPO_FECHADO");
    }

    /**
     * O motor relê as variáveis do {@code SELECT} de um cursor aberto (no nativo, {@code X} sai 1, não 2); o
     * Interpretador recusa a alteração em vez de divergir. O caso do {@code FOR SELECT} é recusado antes, pelo
     * Pré-check ({@code PreCheckTest}).
     */
    @Test
    void variableUsedByAnOpenDeclaredCursorCannotChange() throws Exception {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            var procedure =
                    RoutineParser.parse(RoutineSourceLoader.loadProcedure(connection, "CUR_MUDA_VARIAVEL"));

            PsqlExecutionException error = assertThrows(PsqlExecutionException.class,
                    () -> new Interpreter(connection).execute(procedure, List.of(2)));
            // Linha do texto que o editor do DBeaver mostra: CREATE ... (MINIMO), RETURNS (, X, ), AS e o DECLARE.
            assertEquals("linha 6, coluna 1: alteração de variável usada pelo SELECT de um cursor aberto (MINIMO)"
                    + " não é suportada pelo debugger", error.getMessage());
            connection.rollback();
        }
    }

    /**
     * Nenhum cursor fica aberto no servidor depois da Rotina, termine ela normalmente, por {@code LEAVE}/
     * {@code EXIT} no meio de um laço, ou por erro: a conexão volta a ter só os statements que tinha antes.
     */
    @Test
    void noCursorIsLeftOpen() throws Exception {
        harness.assertSameOutputs("CUR_ABERTOS", false);
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            var procedure = RoutineParser.parse(RoutineSourceLoader.loadProcedure(connection, "CUR_ABERTOS"));
            Interpreter interpreter = new Interpreter(connection);

            int before = settledOpenStatements(connection);
            interpreter.execute(procedure, List.of(false));
            assertEquals(before, settledOpenStatements(connection), "statements abertos depois do fim normal");
            assertThrows(SQLException.class, () -> interpreter.execute(procedure, List.of(true)));
            assertEquals(before, settledOpenStatements(connection), "statements abertos depois de um erro");
            connection.rollback();
        }
    }

    /**
     * {@link #openStatements} quando a contagem para de mudar. Um statement fechado às vezes ainda aparece logo
     * depois do fechamento, e às vezes não (a liberação é assíncrona); um cursor deixado aberto não some nunca.
     */
    private static int settledOpenStatements(Connection connection) throws Exception {
        int previous = openStatements(connection);
        for (int attempt = 0; attempt < 20; attempt++) {
            Thread.sleep(100);
            int current = openStatements(connection);
            if (current == previous) {
                return current;
            }
            previous = current;
        }
        return previous;
    }

    /** Statements alocados na conexão; cada consulta ao MON$ usa uma transação própria, para ver o estado atual. */
    private static int openStatements(Connection connection) throws SQLException {
        try (Connection monitor = database.connect();
                PreparedStatement count = monitor.prepareStatement(
                        "SELECT COUNT(*) FROM MON$STATEMENTS S JOIN MON$ATTACHMENTS A"
                                + " ON A.MON$ATTACHMENT_ID = S.MON$ATTACHMENT_ID"
                                + " WHERE A.MON$ATTACHMENT_ID = ?")) {
            count.setLong(1, attachmentId(connection));
            try (ResultSet resultSet = count.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static long attachmentId(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT CURRENT_CONNECTION FROM RDB$DATABASE");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
