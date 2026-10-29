package dev.fbtools.dbeaver.psqldebug.interp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais de controle de fluxo (issue 03): {@code IF}, {@code WHILE}, labels, {@code LEAVE},
 * {@code CONTINUE} e {@code EXIT}. Os laços deixam uma trilha numa saída texto, para que a ordem das iterações
 * também seja comparada.
 */
class ControlFlowDifferentialTest {

    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        TestDatabase database = TestDatabase.get();
        database.execute("""
                CREATE OR ALTER PROCEDURE DT_IF (A INTEGER, B INTEGER)
                RETURNS (R_IF VARCHAR(20), R_NOT VARCHAR(20), R_ELSE_IF VARCHAR(20), R_SEM_ELSE INTEGER,
                         R_DISTINCT VARCHAR(10), R_BOOLEANO VARCHAR(15), R_PENDENTE VARCHAR(10),
                         R_BLOCOS VARCHAR(20))
                AS
                DECLARE VARIABLE FLAG BOOLEAN;
                BEGIN
                  IF (A > B) THEN R_IF = 'maior'; ELSE R_IF = 'nao maior';
                  IF (NOT (A > B)) THEN R_NOT = 'nao maior'; ELSE R_NOT = 'senao';
                  IF (A IS NULL) THEN R_ELSE_IF = 'A nulo';
                  ELSE IF (B IS NULL) THEN R_ELSE_IF = 'B nulo';
                  ELSE IF (A = B) THEN R_ELSE_IF = 'iguais';
                  ELSE R_ELSE_IF = 'diferentes';
                  R_SEM_ELSE = 0;
                  IF (A = NULL) THEN R_SEM_ELSE = 1;
                  IF (A IS NOT DISTINCT FROM B) THEN R_DISTINCT = 'sim'; ELSE R_DISTINCT = 'nao';
                  FLAG = A > B;
                  IF (FLAG) THEN R_BOOLEANO = 'verdadeiro';
                  ELSE IF (FLAG IS NULL) THEN R_BOOLEANO = 'desconhecido';
                  ELSE R_BOOLEANO = 'falso';
                  IF (A > 0) THEN IF (B > 0) THEN R_PENDENTE = 'ambos'; ELSE R_PENDENTE = 'so A';
                  IF (A > 0 OR B > 0) THEN
                  BEGIN
                    R_BLOCOS = 'algum';
                    IF (A > 0 AND B > 0) THEN
                    BEGIN
                      R_BLOCOS = R_BLOCOS || ' e ambos';
                    END
                  END
                  ELSE
                  BEGIN
                    R_BLOCOS = 'nenhum';
                  END
                END""", """
                CREATE OR ALTER PROCEDURE DT_WHILE (N INTEGER)
                RETURNS (TRILHA VARCHAR(200), I INTEGER)
                AS
                BEGIN
                  TRILHA = '';
                  I = 0;
                  WHILE (I < N) DO
                  BEGIN
                    I = I + 1;
                    IF (I = 3) THEN CONTINUE;
                    IF (I = 6) THEN LEAVE;
                    TRILHA = TRILHA || I || ',';
                  END
                  WHILE (I > N - 2) DO
                  BEGIN
                    TRILHA = TRILHA || 'b';
                    BREAK;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE DT_ANINHADO (N INTEGER)
                RETURNS (TRILHA VARCHAR(500))
                AS
                DECLARE VARIABLE I INTEGER = 0;
                DECLARE VARIABLE J INTEGER;
                BEGIN
                  TRILHA = '';
                  WHILE (I < N) DO
                  BEGIN
                    I = I + 1;
                    J = 0;
                    WHILE (J < N) DO
                    BEGIN
                      J = J + 1;
                      IF (J = 2) THEN CONTINUE;
                      IF (J = I) THEN LEAVE;
                      TRILHA = TRILHA || I || '.' || J || ' ';
                    END
                    TRILHA = TRILHA || '| ';
                  END
                END""", """
                CREATE OR ALTER PROCEDURE DT_LABELS (N INTEGER)
                RETURNS (TRILHA VARCHAR(500))
                AS
                DECLARE VARIABLE I INTEGER = 0;
                DECLARE VARIABLE J INTEGER;
                BEGIN
                  TRILHA = '';
                  Externo: WHILE (I < N) DO
                  BEGIN
                    I = I + 1;
                    J = 0;
                    "Interno": WHILE (J < N) DO
                    BEGIN
                      J = J + 1;
                      IF (J = 2) THEN CONTINUE "Interno";
                      IF (I = 2) THEN CONTINUE EXTERNO;
                      IF (J = 4) THEN LEAVE "Interno";
                      IF (I * J = 9) THEN LEAVE externo;
                      TRILHA = TRILHA || I || '.' || J || ' ';
                    END
                    TRILHA = TRILHA || '| ';
                  END
                  TRILHA = TRILHA || 'fim';
                END""", """
                CREATE OR ALTER PROCEDURE DT_EXIT (N INTEGER)
                RETURNS (TRILHA VARCHAR(200), PASSOU_DO_LACO INTEGER)
                AS
                DECLARE VARIABLE I INTEGER = 0;
                DECLARE VARIABLE J INTEGER;
                BEGIN
                  TRILHA = '';
                  PASSOU_DO_LACO = 0;
                  WHILE (I < 4) DO
                  BEGIN
                    I = I + 1;
                    J = 0;
                    WHILE (J < 4) DO
                    BEGIN
                      J = J + 1;
                      IF (I = N AND J = 2) THEN EXIT;
                      TRILHA = TRILHA || I || J || ' ';
                    END
                  END
                  PASSOU_DO_LACO = 1;
                END""", """
                CREATE OR ALTER PROCEDURE DT_PALAVRAS_CHAVE (LIMITE INTEGER)
                RETURNS (R INTEGER)
                AS
                DECLARE VARIABLE IF INTEGER = 1;
                DECLARE VARIABLE DO INTEGER = 2;
                DECLARE VARIABLE EXIT INTEGER = 3;
                DECLARE VARIABLE LEAVE INTEGER = 0;
                DECLARE VARIABLE CONTINUE INTEGER = 0;
                BEGIN
                  DO: WHILE (LEAVE < 5) DO
                  BEGIN
                    LEAVE = LEAVE + 1;
                    IF (LEAVE = IF) THEN CONTINUE DO;
                    CONTINUE = CONTINUE + DO * EXIT;
                    IF (CONTINUE > LIMITE) THEN LEAVE DO;
                  END
                  R = CONTINUE + LEAVE * 100;
                END""");
        harness = new DifferentialHarness(database);
    }

    @Test
    void ifWithNullConditionTakesTheElseBranch() throws Exception {
        harness.assertSameOutputs("DT_IF", null, null);
        harness.assertSameOutputs("DT_IF", 1, null);
        harness.assertSameOutputs("DT_IF", null, 1);
    }

    @Test
    void ifWithKnownConditions() throws Exception {
        harness.assertSameOutputs("DT_IF", 2, 1);
        harness.assertSameOutputs("DT_IF", 1, 2);
        harness.assertSameOutputs("DT_IF", 3, 3);
        harness.assertSameOutputs("DT_IF", -1, -2);
    }

    @Test
    void whileWithLeaveAndContinueWithoutLabel() throws Exception {
        harness.assertSameOutputs("DT_WHILE", 10);
        harness.assertSameOutputs("DT_WHILE", 4);
        harness.assertSameOutputs("DT_WHILE", 0);
        harness.assertSameOutputs("DT_WHILE", (Object) null);
    }

    @Test
    void leaveAndContinueWithoutLabelAffectOnlyTheInnermostLoop() throws Exception {
        harness.assertSameOutputs("DT_ANINHADO", 4);
        harness.assertSameOutputs("DT_ANINHADO", 1);
    }

    @Test
    void leaveAndContinueWithLabelTargetTheNamedLoop() throws Exception {
        harness.assertSameOutputs("DT_LABELS", 5);
        harness.assertSameOutputs("DT_LABELS", 3);
        harness.assertSameOutputs("DT_LABELS", (Object) null);
    }

    @Test
    void exitInTheMiddleOfNestedLoopsEndsTheRoutine() throws Exception {
        harness.assertSameOutputs("DT_EXIT", 3);
        harness.assertSameOutputs("DT_EXIT", 1);
        harness.assertSameOutputs("DT_EXIT", (Object) null);
    }

    @Test
    void nonReservedKeywordsAsVariablesAndLabels() throws Exception {
        harness.assertSameOutputs("DT_PALAVRAS_CHAVE", 10);
        harness.assertSameOutputs("DT_PALAVRAS_CHAVE", 100);
    }
}
