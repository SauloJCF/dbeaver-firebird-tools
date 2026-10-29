package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;
import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais de sub-rotinas internas (issue 11): a chamada elegível é interpretada ou vai ao servidor pela
 * cópia no Package temporário de debug, e a chamada dentro de expressão ou Fatia DML sempre vai ao servidor pela
 * cópia. Toda combinação tem de dar o mesmo resultado que a execução nativa.
 */
class SubroutineDifferentialTest {

    /** Interpreta só as chamadas à Rotina com este nome; as outras vão ao servidor. */
    private static CallPolicy only(String name) {
        return (call, caller) -> call.name().equals(name);
    }

    private static final List<CallPolicy> POLICIES = List.of(CallPolicy.INTERPRET, CallPolicy.SERVER);

    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        TestDatabase database = TestDatabase.get();
        database.execute(
                "CREATE TABLE SR_LOG (ORDEM INTEGER, TEXTO VARCHAR(100))",
                "CREATE TABLE SR_BASE (ORDEM INTEGER)",
                "INSERT INTO SR_BASE VALUES (1)",
                "INSERT INTO SR_BASE VALUES (2)",
                "CREATE TABLE SR_T (A INTEGER, B INTEGER)",
                // Function avulsa com o nome da sub-function: dentro da Rotina, quem vale é a sub-function.
                """
                CREATE OR ALTER FUNCTION SR_DOBRO (X INTEGER)
                RETURNS INTEGER
                AS
                BEGIN
                  RETURN -1;
                END""", """
                CREATE OR ALTER PROCEDURE SR_CONDICIONAL (A INTEGER, B INTEGER)
                RETURNS (R1 INTEGER, R2 INTEGER, R3 INTEGER, R4 INTEGER)
                AS
                  DECLARE FUNCTION SR_DOBRO (X INTEGER) RETURNS INTEGER
                  AS
                  BEGIN
                    INSERT INTO SR_LOG VALUES (:X, 'dobro ' || COALESCE(:X, -999));
                    RETURN X * 2;
                  END
                BEGIN
                  -- A sub-function só roda quando o motor a rodaria: cada execução deixa uma linha em SR_LOG.
                  R1 = CASE WHEN A > 0 THEN SR_DOBRO(A) ELSE 0 END;
                  R2 = COALESCE(B, SR_DOBRO(A + 100));
                  R3 = SR_DOBRO(B);
                  IF (SR_DOBRO(A) > 0 OR SR_DOBRO(B) > 0) THEN R4 = 1;
                  INSERT INTO SR_LOG SELECT ORDEM, 'dml ' || SR_DOBRO(ORDEM) FROM SR_BASE WHERE :A > 0;
                END""", """
                CREATE OR ALTER PROCEDURE SR_PROCS (N INTEGER)
                RETURNS (TOTAL INTEGER, TRILHA VARCHAR(200), CONTAGEM INTEGER)
                AS
                  DECLARE I INTEGER;
                  DECLARE PROCEDURE GERA (K INTEGER) RETURNS (I INTEGER)
                  AS
                  BEGIN
                    I = 0;
                    WHILE (I < K) DO
                    BEGIN
                      I = I + 1;
                      INSERT INTO SR_LOG VALUES (:I, 'gera ' || :I);
                      SUSPEND;
                    END
                  END
                  DECLARE PROCEDURE SOMA (A INTEGER, B INTEGER = 0) RETURNS (S INTEGER)
                  AS
                  BEGIN
                    S = A + B;
                  END
                  DECLARE FUNCTION ROTULO (X INTEGER) RETURNS VARCHAR(20)
                  AS
                  BEGIN
                    RETURN 'r' || X;
                  END
                  DECLARE FUNCTION VIA_IRMA (X INTEGER) RETURNS VARCHAR(30)
                  AS
                    DECLARE S INTEGER;
                  BEGIN
                    EXECUTE PROCEDURE SOMA (X, 10) RETURNING_VALUES S;
                    RETURN ROTULO(S) || '+' || (SELECT COUNT(*) FROM GERA(2));
                  END
                BEGIN
                  TOTAL = 0;
                  TRILHA = '';
                  FOR SELECT I FROM GERA(:N) INTO :I DO
                  BEGIN
                    EXECUTE PROCEDURE SOMA (TOTAL, I) RETURNING_VALUES TOTAL;
                    TRILHA = TRILHA || ROTULO(I) || ';';
                  END
                  EXECUTE PROCEDURE SOMA (TOTAL) RETURNING_VALUES TOTAL;
                  SELECT COUNT(*) FROM GERA(:N + 1) INTO :CONTAGEM;
                  TRILHA = TRILHA || VIA_IRMA(N);
                END""", """
                CREATE OR ALTER PROCEDURE SR_MAE (ENTRADA INTEGER)
                RETURNS (LIDO INTEGER, ALTERADO INTEGER, SAIDA INTEGER, LINHAS VARCHAR(100))
                AS
                  DECLARE V INTEGER = 3;
                  DECLARE FUNCTION LE RETURNS INTEGER AS BEGIN RETURN V + ENTRADA; END
                  DECLARE PROCEDURE MUDA (NOVO INTEGER) AS BEGIN V = NOVO; SAIDA = NOVO * 10; END
                  DECLARE PROCEDURE LISTA RETURNS (X INTEGER)
                  AS
                  BEGIN
                    X = V;
                    SUSPEND;
                    V = V + 1;
                    X = V;
                    SUSPEND;
                  END
                  DECLARE FUNCTION PURA (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X + 1; END
                  DECLARE X INTEGER;
                BEGIN
                  V = 10;
                  LIDO = LE();
                  EXECUTE PROCEDURE MUDA (ENTRADA + 1);
                  ALTERADO = PURA(V) - 1;
                  LINHAS = '';
                  FOR SELECT X FROM LISTA INTO :X DO
                  BEGIN
                    LINHAS = LINHAS || X || ',';
                    V = V * 2;
                  END
                  LINHAS = LINHAS || V;
                END""", """
                CREATE OR ALTER PROCEDURE SR_ERRO (K INTEGER)
                RETURNS (R INTEGER, CODIGO INTEGER)
                AS
                  DECLARE FUNCTION DIVIDE (X INTEGER) RETURNS INTEGER
                  AS
                  BEGIN
                    INSERT INTO SR_LOG VALUES (:X, 'divide');
                    RETURN 10 / X;
                  END
                BEGIN
                  BEGIN
                    R = DIVIDE(K);
                    WHEN ANY DO CODIGO = GDSCODE;
                  END
                  BEGIN
                    INSERT INTO SR_LOG VALUES (:K, 'antes');
                    R = COALESCE(R, 0) + DIVIDE(K);
                    WHEN ANY DO CODIGO = COALESCE(CODIGO, 0) + 1;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE SR_CHAMA (A INTEGER)
                RETURNS (R1 INTEGER, R2 INTEGER, R3 INTEGER, R4 INTEGER, PROPRIA INTEGER)
                AS
                  DECLARE FUNCTION SR_DOBRO (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X * 3; END
                BEGIN
                  -- Cada Rotina tem as suas sub-rotinas, cada uma no seu package.
                  EXECUTE PROCEDURE SR_CONDICIONAL (:A, NULL) RETURNING_VALUES :R1, :R2, :R3, :R4;
                  PROPRIA = SR_DOBRO(A) + 0;
                END""",
                "CREATE PACKAGE SR_PKG AS BEGIN PROCEDURE ENTRADA (N INTEGER) RETURNS (R INTEGER); "
                        + "FUNCTION PUB (X INTEGER) RETURNS INTEGER; END", """
                CREATE PACKAGE BODY SR_PKG
                AS
                BEGIN
                  FUNCTION PUB (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X + 1000; END
                  PROCEDURE ENTRADA (N INTEGER) RETURNS (R INTEGER)
                  AS
                    -- Dentro da sub-rotina, a rotina da package só é alcançada com o nome da package.
                    DECLARE FUNCTION SUB (X INTEGER) RETURNS INTEGER AS BEGIN RETURN SR_PKG.PUB(X) * 2; END
                  BEGIN
                    R = SUB(N) + PUB(1);
                  END
                END""", """
                CREATE TRIGGER SR_T_BI FOR SR_T BEFORE INSERT
                AS
                  DECLARE FUNCTION K (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X * 2; END
                BEGIN
                  NEW.B = K(NEW.A) + 1;
                  INSERT INTO SR_LOG VALUES (K(NEW.B), 'trigger');
                END""");
        harness = new DifferentialHarness(database).tables("SR_LOG");
    }

    @Test
    void subFunctionInConditionalExpressionRunsOnlyWhenTheEngineWouldRunIt() throws SQLException {
        for (CallPolicy policy : List.of(CallPolicy.INTERPRET, CallPolicy.SERVER, only("SR_DOBRO"))) {
            harness.calls(policy).assertSameOutputs("SR_CONDICIONAL", 1, null);
            harness.calls(policy).assertSameOutputs("SR_CONDICIONAL", 0, 5);
            harness.calls(policy).assertSameOutputs("SR_CONDICIONAL", -1, 3);
        }
    }

    @Test
    void subProceduresInLoopsCallsAndDml() throws SQLException {
        for (CallPolicy policy : List.of(CallPolicy.INTERPRET, CallPolicy.SERVER, only("SOMA"), only("GERA"))) {
            harness.calls(policy).assertSameOutputs("SR_PROCS", 3);
            harness.calls(policy).assertSameOutputs("SR_PROCS", 0);
        }
    }

    @Test
    void subroutinesReadAndChangeTheVariablesOfTheirRoutine() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            harness.calls(policy).assertSameOutputs("SR_MAE", 5);
        }
    }

    @Test
    void subroutineChangingAVariableUsedByAnOpenCursorIsRefused() throws Exception {
        RoutineDefinition block = RoutineParser.parse("""
                EXECUTE BLOCK RETURNS (R INTEGER)
                AS
                  DECLARE V INTEGER = 0;
                  DECLARE PROCEDURE MUDA AS BEGIN V = V + 1; END
                BEGIN
                  FOR SELECT ORDEM FROM SR_BASE WHERE ORDEM > :V INTO :R DO
                    EXECUTE PROCEDURE MUDA;
                END""");
        try (Connection connection = TestDatabase.get().connect()) {
            connection.setAutoCommit(false);
            PsqlExecutionException error = assertThrows(PsqlExecutionException.class,
                    () -> new Interpreter(connection).execute(block, List.of()));
            assertTrue(error.getMessage().contains(UnsupportedConstruct.CURSOR_VARIABLE_CHANGED.syntax()),
                    error.getMessage());
            connection.rollback();
        }
    }

    @Test
    void errorInsideSubroutineIsHandledByTheCaller() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            harness.calls(policy).assertSameOutputs("SR_ERRO", 0);
            harness.calls(policy).assertSameOutputs("SR_ERRO", 2);
        }
    }

    @Test
    void eachRoutineUsesItsOwnSubroutines() throws SQLException {
        for (CallPolicy policy : List.of(CallPolicy.INTERPRET, CallPolicy.SERVER, only("SR_CONDICIONAL"))) {
            harness.calls(policy).assertSameOutputs("SR_CHAMA", 4);
        }
    }

    @Test
    void subroutineOfPackageRoutine() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            harness.calls(policy).assertSamePackageOutputs("SR_PKG", "ENTRADA", 7);
        }
    }

    @Test
    void subroutineOfExecuteBlockAndOfTrigger() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            harness.calls(policy).assertSameExecuteBlock("""
                    EXECUTE BLOCK (A INTEGER = ?)
                    RETURNS (R INTEGER, S VARCHAR(20))
                    AS
                      DECLARE FUNCTION TRIPLO (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X * 3; END
                      DECLARE PROCEDURE ECO (T VARCHAR(10)) RETURNS (U VARCHAR(20)) AS BEGIN U = T || T; END
                    BEGIN
                      R = TRIPLO(A) + 1;
                      EXECUTE PROCEDURE ECO ('ab') RETURNING_VALUES S;
                      SUSPEND;
                      SELECT TRIPLO(ORDEM) FROM SR_BASE WHERE ORDEM = 2 INTO :R;
                      SUSPEND;
                    END""", 4);
            harness.calls(policy).assertSameTrigger("SR_T_BI", "INSERT INTO SR_T (A) VALUES (4)",
                    "SELECT * FROM SR_T", Map.of("NEW.A", 4, "INSERTING", true));
        }
    }
}
