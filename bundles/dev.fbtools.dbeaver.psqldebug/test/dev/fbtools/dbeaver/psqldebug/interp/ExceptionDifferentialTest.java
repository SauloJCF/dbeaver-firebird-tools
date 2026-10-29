package dev.fbtools.dbeaver.psqldebug.interp;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais de {@code EXCEPTION}, {@code WHEN} e do savepoint implícito dos blocos com handler (issue
 * 07). Os handlers deixam {@code GDSCODE}, {@code SQLCODE} e {@code SQLSTATE} nas saídas, e o estado das tabelas
 * mostra o que cada savepoint desfez.
 */
class ExceptionDifferentialTest {

    /** Quantos erros {@link #ERRORS} sabe levantar, pelo parâmetro {@code TIPO}. */
    private static final int ERROR_KINDS = 15;

    /** Variáveis que {@link #ERRORS} usa. */
    private static final String ERROR_VARIABLES = """
            DECLARE X INTEGER;
            DECLARE Z INTEGER = 0;
            DECLARE S VARCHAR(3);
            DECLARE NN INTEGER NOT NULL = 1;
            DECLARE D NUMERIC(18,2) = 1.5;
            """;

    /** Um erro diferente para cada {@code TIPO}: exceções de usuário, erros do servidor e erros que o Interpretador detecta. */
    private static final String ERRORS = """
            IF (TIPO = 1) THEN EXCEPTION E_SIMPLES;
            IF (TIPO = 2) THEN EXCEPTION E_SIMPLES 'outra ' || 'mensagem ' || TIPO;
            IF (TIPO = 3) THEN EXCEPTION E_PARAM USING (D, NULL);
            IF (TIPO = 4) THEN EXCEPTION "E_minúscula";
            IF (TIPO = 5) THEN X = 1 / Z;
            IF (TIPO = 6) THEN S = 'muito longo';
            IF (TIPO = 7) THEN X = CAST('abc' AS INTEGER);
            IF (TIPO = 8) THEN INSERT INTO EXC_ITEM (ID, N) VALUES (1, 1);
            IF (TIPO = 9) THEN INSERT INTO EXC_ITEM (ID, N) VALUES (9, -1);
            IF (TIPO = 10) THEN INSERT INTO EXC_FILHO (ID) VALUES (99);
            IF (TIPO = 11) THEN SELECT ID FROM EXC_ITEM INTO :X;
            IF (TIPO = 12) THEN EXECUTE STATEMENT 'SELECT * FROM NAO_EXISTE' INTO :X;
            IF (TIPO = 13) THEN NN = NULL;
            IF (TIPO = 14) THEN X = 2147483647 * 2147483647 * 4;
            IF (TIPO = 15) THEN EXCEPTION E_PARAM 'mensagem própria @1';
            """;

    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        TestDatabase database = TestDatabase.get();
        database.execute(
                "CREATE TABLE EXC_ITEM (ID INTEGER NOT NULL PRIMARY KEY, N INTEGER CHECK (N > 0))",
                "CREATE TABLE EXC_FILHO (ID INTEGER REFERENCES EXC_ITEM (ID))",
                "CREATE TABLE EXC_LOG (N INTEGER, TEXTO VARCHAR(200))",
                "INSERT INTO EXC_ITEM VALUES (1, 1)",
                "INSERT INTO EXC_ITEM VALUES (2, 2)",
                "CREATE EXCEPTION E_SIMPLES 'mensagem padrão'",
                "CREATE EXCEPTION E_PARAM 'valor @1 e @2'",
                "CREATE EXCEPTION \"E_minúscula\" 'nome entre aspas'", """
                CREATE OR ALTER PROCEDURE EXC_ERRO (TIPO INTEGER)
                AS
                %s
                BEGIN
                  INSERT INTO EXC_LOG (TEXTO) VALUES ('antes do erro');
                %s
                END""".formatted(ERROR_VARIABLES, ERRORS), """
                CREATE OR ALTER PROCEDURE EXC_CODIGOS (TIPO INTEGER)
                RETURNS (FORA_G INTEGER, FORA_SC INTEGER, FORA_SS CHAR(5), G INTEGER, SC INTEGER, SS CHAR(5),
                         DEPOIS_G INTEGER)
                AS
                %s
                BEGIN
                  FORA_G = GDSCODE;
                  FORA_SC = SQLCODE;
                  FORA_SS = SQLSTATE;
                  BEGIN
                %s
                  WHEN ANY DO
                  BEGIN
                    G = GDSCODE;
                    SC = SQLCODE;
                    SS = SQLSTATE;
                    INSERT INTO EXC_LOG (N, TEXTO) VALUES (GDSCODE, SQLSTATE || ' ' || SQLCODE);
                  END
                  END
                  DEPOIS_G = GDSCODE;
                END""".formatted(ERROR_VARIABLES, ERRORS), """
                CREATE OR ALTER PROCEDURE EXC_DESFAZ (FALHAR INTEGER)
                RETURNS (TRILHA VARCHAR(200))
                AS
                BEGIN
                  TRILHA = 'inicio';
                  INSERT INTO EXC_LOG (TEXTO) VALUES ('antes do bloco');
                  BEGIN
                    INSERT INTO EXC_LOG (TEXTO) VALUES ('no bloco');
                    UPDATE EXC_ITEM SET N = N + 10;
                    TRILHA = TRILHA || ' bloco';
                    IF (FALHAR = 1) THEN INSERT INTO EXC_ITEM (ID, N) VALUES (1, 1);
                    IF (FALHAR = 2) THEN EXCEPTION E_SIMPLES;
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('bloco interno sem handler');
                      IF (FALHAR = 3) THEN INSERT INTO EXC_ITEM (ID, N) VALUES (2, 2);
                    END
                  WHEN ANY DO
                  BEGIN
                    TRILHA = TRILHA || ' handler ' || GDSCODE;
                    INSERT INTO EXC_LOG (N, TEXTO) VALUES (GDSCODE, 'no handler');
                  END
                  END
                  INSERT INTO EXC_LOG (TEXTO) VALUES ('depois do bloco');
                END""", """
                CREATE OR ALTER PROCEDURE EXC_CONDICOES (TIPO INTEGER)
                RETURNS (R1 VARCHAR(20), R2 VARCHAR(20), R3 VARCHAR(20), R4 VARCHAR(20), R5 VARCHAR(20), R6 VARCHAR(20),
                         RG INTEGER)
                AS
                %s
                BEGIN
                  -- Entre os específicos, o primeiro handler que casa ganha.
                  BEGIN
                %s
                  WHEN SQLCODE -803 DO R1 = 'sqlcode -803';
                  WHEN GDSCODE arith_except, SQLSTATE '22018' DO R1 = 'arith/22018';
                  WHEN EXCEPTION E_PARAM DO R1 = 'e_param';
                  WHEN EXCEPTION E_SIMPLES, EXCEPTION "E_minúscula" DO R1 = 'e_simples/min';
                  WHEN SQLCODE -836 DO R1 = 'outra exceção';
                  WHEN SQLSTATE '21000' DO R1 = 'singleton';
                  WHEN GDSCODE CONVERT_ERROR DO R1 = 'nunca';
                  WHEN SQLCODE -204, GDSCODE not_valid_for_var DO R1 = 'dsql/validação';
                  WHEN SQLSTATE '23000' DO R1 = 'integridade';
                  END
                  -- Sem handler que case, o erro sobe para o bloco de fora.
                  BEGIN
                    BEGIN
                %s
                    WHEN GDSCODE sing_select_err DO R2 = 'interno';
                    END
                  WHEN ANY DO R2 = COALESCE(R2, '') || 'externo';
                  END
                  -- Um WHEN ANY depois de um handler que casou é o que roda, já sem erro.
                  BEGIN
                %s
                  WHEN SQLCODE -836, SQLCODE -802, SQLCODE -803 DO R3 = 'específico';
                  WHEN ANY DO
                  BEGIN
                    R3 = 'any';
                    RG = GDSCODE;
                    EXCEPTION;
                  END
                  END
                  -- O último de vários WHEN ANY.
                  BEGIN
                    X = 1 / Z;
                  WHEN ANY DO R4 = 'any 1';
                  WHEN ANY DO R4 = 'any 2';
                  END
                  -- WHEN ANY antes do específico.
                  BEGIN
                    X = 1 / Z;
                  WHEN ANY DO R5 = 'any';
                  WHEN GDSCODE arith_except DO R5 = 'arith';
                  END
                  BEGIN
                    X = 1 / Z;
                  WHEN SQLSTATE '22012' DO R6 = '22012';
                  END
                END""".formatted(ERROR_VARIABLES, ERRORS, ERRORS, ERRORS), """
                CREATE OR ALTER PROCEDURE EXC_ANINHADO
                RETURNS (TRILHA VARCHAR(300))
                AS
                DECLARE X INTEGER;
                DECLARE Z INTEGER = 0;
                BEGIN
                  TRILHA = '';
                  BEGIN
                    INSERT INTO EXC_LOG (TEXTO) VALUES ('nivel 1');
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('nivel 2');
                      X = CAST('abc' AS INTEGER);
                    WHEN GDSCODE convert_error DO
                    BEGIN
                      TRILHA = TRILHA || 'h2:' || GDSCODE || ' ';
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('handler 2');
                      BEGIN
                        X = 1 / Z;
                      WHEN ANY DO TRILHA = TRILHA || 'h3:' || GDSCODE || ' ';
                      END
                      TRILHA = TRILHA || 'h2 depois do h3:' || GDSCODE || ' ';
                      EXCEPTION;
                      TRILHA = TRILHA || 'relançar sem erro não faz nada ';
                      EXCEPTION E_PARAM USING ('do handler', SQLSTATE);
                    END
                    END
                    TRILHA = TRILHA || 'nunca';
                  WHEN EXCEPTION E_PARAM DO
                  BEGIN
                    TRILHA = TRILHA || 'h1:' || GDSCODE || ' ' || SQLCODE || ' ' || SQLSTATE;
                    INSERT INTO EXC_LOG (TEXTO) VALUES ('handler 1');
                  END
                  END
                END""", """
                CREATE OR ALTER PROCEDURE EXC_RELANCA (TIPO INTEGER)
                RETURNS (TRILHA VARCHAR(200))
                AS
                %s
                BEGIN
                  TRILHA = 'inicio';
                  INSERT INTO EXC_LOG (TEXTO) VALUES ('antes');
                  BEGIN
                %s
                  WHEN ANY DO
                  BEGIN
                    INSERT INTO EXC_LOG (TEXTO) VALUES ('no handler, antes de relançar');
                    EXCEPTION;
                  END
                  END
                END""".formatted(ERROR_VARIABLES, ERRORS), """
                CREATE OR ALTER PROCEDURE EXC_LACO
                RETURNS (TRILHA VARCHAR(300))
                AS
                DECLARE I INTEGER = 0;
                DECLARE ID INTEGER;
                BEGIN
                  TRILHA = '';
                  WHILE (I < 5) DO
                  BEGIN
                    I = I + 1;
                    BEGIN
                      INSERT INTO EXC_LOG (N, TEXTO) VALUES (:I, 'iteracao');
                      IF (I = 2) THEN INSERT INTO EXC_ITEM (ID, N) VALUES (1, 1);
                      IF (I = 4) THEN EXCEPTION E_SIMPLES;
                      TRILHA = TRILHA || I || ' ';
                    WHEN SQLCODE -803 DO TRILHA = TRILHA || 'dup ';
                    WHEN EXCEPTION E_SIMPLES DO
                    BEGIN
                      TRILHA = TRILHA || 'sai ';
                      LEAVE;
                    END
                    END
                  END
                  FOR SELECT ID FROM EXC_ITEM ORDER BY ID INTO :ID DO
                  BEGIN
                    BEGIN
                      UPDATE EXC_ITEM SET N = -1 WHERE ID = :ID;
                    WHEN ANY DO TRILHA = TRILHA || 'check' || ID || ' ';
                    END
                    INSERT INTO EXC_LOG (N, TEXTO) VALUES (:ID, 'depois do check');
                  END
                  BEGIN
                    FOR SELECT ID FROM EXC_ITEM ORDER BY ID INTO :ID DO
                    BEGIN
                      INSERT INTO EXC_LOG (N, TEXTO) VALUES (:ID, 'antes de falhar no laço');
                      IF (ID = 2) THEN EXCEPTION E_SIMPLES;
                    END
                  WHEN ANY DO TRILHA = TRILHA || 'laço interrompido';
                  END
                END""", """
                CREATE OR ALTER PROCEDURE EXC_ERRO_NO_HANDLER
                RETURNS (TRILHA VARCHAR(200))
                AS
                DECLARE X INTEGER;
                DECLARE Z INTEGER = 0;
                BEGIN
                  TRILHA = '';
                  BEGIN
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('bloco');
                      X = 1 / Z;
                    WHEN ANY DO
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('handler que falha');
                      INSERT INTO EXC_ITEM (ID, N) VALUES (2, 2);
                    END
                    END
                  WHEN SQLCODE -803 DO TRILHA = 'externo pegou ' || GDSCODE;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE EXC_SAVEPOINTS (TIPO INTEGER)
                AS
                DECLARE X INTEGER;
                DECLARE Z INTEGER = 0;
                DECLARE I INTEGER;
                BEGIN
                  -- Statement terminado do bloco fica; o que falhou é desfeito.
                  IF (TIPO = 1) THEN
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('a');
                      INSERT INTO EXC_ITEM (ID, N) VALUES (1, 1);
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h');
                    END
                  -- Bloco interno (com handler que não casa, ou sem handler) é desfeito inteiro.
                  IF (TIPO = 2) THEN
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('a');
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('b');
                        EXCEPTION E_SIMPLES;
                      WHEN SQLCODE -1 DO X = 1;
                      END
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h');
                    END
                  IF (TIPO = 3) THEN
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('a');
                      IF (1 = 1) THEN
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('b');
                        EXCEPTION E_SIMPLES;
                      END
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h');
                    END
                  -- Erro no handler: o bloco e o handler são desfeitos pelo bloco de fora.
                  IF (TIPO = 4) THEN
                    BEGIN
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('a');
                        X = 1 / Z;
                      WHEN ANY DO
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('h1');
                        EXCEPTION E_SIMPLES;
                      END
                      END
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h2');
                    END
                  -- ... a menos que um bloco dentro do handler tenha tratado um erro antes.
                  IF (TIPO = 5 OR TIPO = 6) THEN
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('n1');
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('n2');
                        X = 1 / Z;
                      WHEN ANY DO
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('h2');
                        BEGIN
                          IF (TIPO = 6) THEN INSERT INTO EXC_LOG (TEXTO) VALUES ('q');
                          X = 1 / Z;
                        WHEN ANY DO IF (TIPO = 6) THEN INSERT INTO EXC_LOG (TEXTO) VALUES ('qh');
                        END
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('depois do q');
                        BEGIN
                          X = 1 / Z;
                        WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('outro tratado');
                        END
                        EXCEPTION E_SIMPLES;
                      END
                      END
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h1');
                    END
                  -- Fora de handler, um erro tratado antes não muda nada.
                  IF (TIPO = 7) THEN
                    BEGIN
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('n1');
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('n2');
                        BEGIN
                          X = 1 / Z;
                        WHEN ANY DO X = 0;
                        END
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('n3');
                        EXCEPTION E_SIMPLES;
                      END
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h1');
                    END
                  -- Tratado num handler mais interno: só o bloco dele perde a proteção.
                  IF (TIPO = 8) THEN
                    BEGIN
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('a');
                        X = 1 / Z;
                      WHEN ANY DO
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('h1');
                        BEGIN
                          INSERT INTO EXC_LOG (TEXTO) VALUES ('b');
                          X = 1 / Z;
                        WHEN ANY DO
                        BEGIN
                          INSERT INTO EXC_LOG (TEXTO) VALUES ('h2');
                          BEGIN
                            X = 1 / Z;
                          WHEN ANY DO X = 0;
                          END
                          EXCEPTION E_SIMPLES;
                        END
                        END
                      END
                      END
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h0');
                    END
                  -- FOR SELECT: savepoint próprio só se o nível atual já tinha alterações.
                  IF (TIPO BETWEEN 9 AND 14) THEN
                    BEGIN
                      IF (TIPO = 10) THEN INSERT INTO EXC_LOG (TEXTO) VALUES ('a');
                      IF (TIPO = 11) THEN UPDATE EXC_ITEM SET N = N WHERE ID < 0;
                      IF (TIPO = 12) THEN
                      BEGIN
                        INSERT INTO EXC_LOG (TEXTO) VALUES ('a');
                      END
                      IF (TIPO = 13) THEN SELECT ID FROM EXC_ITEM WHERE ID = 1 INTO :X;
                      IF (TIPO = 14) THEN
                        WHILE (1 = 1) DO
                        BEGIN
                          FOR SELECT ID FROM EXC_ITEM ORDER BY ID INTO :I DO
                          BEGIN
                            INSERT INTO EXC_LOG (N, TEXTO) VALUES (:I, 'f');
                            IF (I = 2) THEN EXCEPTION E_SIMPLES;
                          END
                        END
                      FOR SELECT ID FROM EXC_ITEM ORDER BY ID INTO :I DO
                      BEGIN
                        INSERT INTO EXC_LOG (N, TEXTO) VALUES (:I, 'f');
                        IF (I = 2) THEN EXCEPTION E_SIMPLES;
                      END
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h');
                    END
                  -- LEAVE de dentro de um bloco com handler leva o que ele fez para o nível de baixo.
                  IF (TIPO = 15) THEN
                    BEGIN
                      WHILE (1 = 1) DO
                      BEGIN
                        BEGIN
                          INSERT INTO EXC_LOG (TEXTO) VALUES ('antes do leave');
                          LEAVE;
                        WHEN ANY DO X = 0;
                        END
                      END
                      INSERT INTO EXC_LOG (TEXTO) VALUES ('depois do laço');
                      EXCEPTION E_SIMPLES;
                    WHEN ANY DO INSERT INTO EXC_LOG (TEXTO) VALUES ('h');
                    END
                END""", """
                CREATE OR ALTER PROCEDURE EXC_PARAMETROS (V NUMERIC(18,2), T VARCHAR(10), N INTEGER)
                AS
                DECLARE D DOUBLE PRECISION = 1.5;
                DECLARE DT DATE = '2024-02-29';
                DECLARE B BOOLEAN = TRUE;
                DECLARE C CHAR(4) = 'ab';
                BEGIN
                  IF (N IS NULL) THEN EXCEPTION E_PARAM USING (V, T);
                  IF (N = 1) THEN EXCEPTION E_PARAM USING (D, DT);
                  IF (N = 2) THEN EXCEPTION E_PARAM USING (B, C);
                  EXCEPTION E_PARAM USING (V * N, T || '|' || D || '|' || DT || '|' || B || '|' || C || '|');
                END""");
        harness = new DifferentialHarness(database).tables("EXC_ITEM", "EXC_FILHO", "EXC_LOG");
    }

    @Test
    void unhandledErrorEndsTheRoutineWithTheSameErrorAsTheEngine() throws Exception {
        for (int tipo = 1; tipo <= ERROR_KINDS; tipo++) {
            harness.assertSameOutputs("EXC_ERRO", tipo);
        }
    }

    @Test
    void handlerSeesGdsCodeSqlCodeAndSqlStateLikeTheEngine() throws Exception {
        for (int tipo = 1; tipo <= ERROR_KINDS; tipo++) {
            harness.assertSameOutputs("EXC_CODIGOS", tipo);
        }
    }

    @Test
    void blockWithHandlerUndoesItsWorkBeforeTheHandler() throws Exception {
        harness.assertSameOutputs("EXC_DESFAZ", 0);
        harness.assertSameOutputs("EXC_DESFAZ", 1);
        harness.assertSameOutputs("EXC_DESFAZ", 2);
        harness.assertSameOutputs("EXC_DESFAZ", 3);
    }

    /**
     * O que um erro tratado desfaz segue a pilha de savepoints do motor: só o statement em andamento do bloco com
     * {@code WHEN}, com os casos do {@code FOR SELECT} e de erro tratado dentro de handler.
     */
    @Test
    void handledErrorUndoesWhatTheEngineUndoes() throws Exception {
        for (int tipo = 1; tipo <= 15; tipo++) {
            harness.assertSameOutputs("EXC_SAVEPOINTS", tipo);
        }
    }

    @Test
    void handlerChoiceFollowsTheEngine() throws Exception {
        for (int tipo = 1; tipo <= ERROR_KINDS; tipo++) {
            harness.assertSameOutputs("EXC_CONDICOES", tipo);
        }
    }

    @Test
    void nestedHandlersAndReraise() throws Exception {
        harness.assertSameOutputs("EXC_ANINHADO");
        for (int tipo = 1; tipo <= ERROR_KINDS; tipo++) {
            harness.assertSameOutputs("EXC_RELANCA", tipo);
        }
    }

    @Test
    void handlersInsideLoops() throws Exception {
        harness.assertSameOutputs("EXC_LACO");
    }

    @Test
    void errorInsideAHandlerGoesToTheOuterBlock() throws Exception {
        harness.assertSameOutputs("EXC_ERRO_NO_HANDLER");
    }

    @Test
    void userExceptionArgumentsAreFormattedByTheServer() throws Exception {
        harness.assertSameOutputs("EXC_PARAMETROS", new BigDecimal("12.50"), "texto", null);
        harness.assertSameOutputs("EXC_PARAMETROS", null, null, 1);
        harness.assertSameOutputs("EXC_PARAMETROS", null, null, 2);
        harness.assertSameOutputs("EXC_PARAMETROS", new BigDecimal("-3.25"), "x", 3);
    }
}
