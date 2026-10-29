package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais de chamadas entre Rotinas (issue 10): cada chamada elegível interpretada ou executada no
 * servidor tem de dar o mesmo resultado que a execução nativa da Rotina raiz.
 */
class CallDifferentialTest {

    /** Interpreta toda chamada elegível, menos as da Rotina com este nome, que vão para o servidor. */
    private static CallPolicy allBut(String name) {
        return (call, caller) -> !call.name().equals(name);
    }

    private static final List<CallPolicy> POLICIES =
            List.of(CallPolicy.INTERPRET, CallPolicy.SERVER, allBut("CH_B"), allBut("CH_C"));

    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        TestDatabase database = TestDatabase.get();
        database.execute(
                "CREATE TABLE CH_LOG (ORDEM INTEGER, TEXTO VARCHAR(100))",
                "CREATE TABLE CH_BASE (ORDEM INTEGER)",
                "INSERT INTO CH_BASE VALUES (1)",
                "INSERT INTO CH_BASE VALUES (2)",
                "INSERT INTO CH_BASE VALUES (3)", """
                CREATE OR ALTER PROCEDURE CH_TALVEZ (K INTEGER)
                AS
                BEGIN
                  IF (K > 0) THEN INSERT INTO CH_LOG VALUES (:K, 'talvez');
                END""", """
                CREATE OR ALTER PROCEDURE CH_NIVEL_FOR (K INTEGER)
                RETURNS (CODIGO INTEGER)
                AS
                DECLARE I INTEGER;
                BEGIN
                  BEGIN
                    EXECUTE PROCEDURE CH_TALVEZ (:K);
                    -- O FOR só ganha nível de savepoint se o bloco já alterou algo (aqui, pela CH_TALVEZ). Com o
                    -- nível, o erro tratado desfaz todas as iterações; sem ele, só a iteração em andamento.
                    FOR SELECT ORDEM FROM CH_BASE ORDER BY ORDEM INTO :I DO
                    BEGIN
                      INSERT INTO CH_LOG VALUES (:I, 'iteração ' || :I);
                      IF (I = 2) THEN I = I / 0;
                    END
                    WHEN ANY DO CODIGO = GDSCODE;
                  END
                END""", """
                CREATE OR ALTER FUNCTION CH_DOBRO (X INTEGER)
                RETURNS INTEGER
                AS
                BEGIN
                  INSERT INTO CH_LOG VALUES (:X, 'dobro ' || :X);
                  RETURN X * 2;
                END""", """
                CREATE OR ALTER PROCEDURE CH_C (N INTEGER)
                RETURNS (I INTEGER, Q NUMERIC(9,2))
                AS
                BEGIN
                  I = 0;
                  WHILE (I < N) DO
                  BEGIN
                    I = I + 1;
                    Q = I * 1.5;
                    INSERT INTO CH_LOG VALUES (:I, 'C antes ' || :I);
                    SUSPEND;
                    INSERT INTO CH_LOG VALUES (:I, 'C depois ' || :I);
                  END
                END""", """
                CREATE OR ALTER PROCEDURE CH_B (N INTEGER, LIMITE INTEGER = 99)
                RETURNS (TOTAL NUMERIC(18,2), TRILHA VARCHAR(100))
                AS
                DECLARE I INTEGER;
                DECLARE Q NUMERIC(9,2);
                DECLARE D INTEGER;
                BEGIN
                  TOTAL = 0;
                  TRILHA = '';
                  FOR SELECT I, Q FROM CH_C(:N) INTO :I, :Q DO
                  BEGIN
                    INSERT INTO CH_LOG VALUES (:I, 'B ' || :I);
                    D = CH_DOBRO(I);
                    TOTAL = TOTAL + Q;
                    TRILHA = TRILHA || I || ':' || D || ';';
                    IF (I = LIMITE) THEN LEAVE;
                  END
                  TRILHA = TRILHA || 'rc=' || ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE CH_A (N INTEGER, LIMITE INTEGER)
                RETURNS (TOTAL NUMERIC(18,2), TRILHA VARCHAR(100), DOBRO INTEGER, C INTEGER, EXTRA VARCHAR(100))
                AS
                BEGIN
                  EXECUTE PROCEDURE CH_B (:N + 0.4, :LIMITE) RETURNING_VALUES :TOTAL, :TRILHA; -- 3.4 vira 3
                  TRILHA = TRILHA || '/rc=' || ROW_COUNT;
                  DOBRO = CH_DOBRO(N);
                  C = COALESCE(NULL, 7); -- função interna: não há o que interpretar
                  EXECUTE PROCEDURE CH_B 1 RETURNING_VALUES (:C, :EXTRA); -- sem parênteses; LIMITE pelo padrão
                END""", """
                CREATE OR ALTER PROCEDURE CH_FALHA (K INTEGER)
                RETURNS (R INTEGER)
                AS
                BEGIN
                  INSERT INTO CH_LOG VALUES (:K, 'falha antes');
                  R = 10 / K;
                  INSERT INTO CH_LOG VALUES (:K, 'falha depois');
                END""", """
                CREATE OR ALTER FUNCTION CH_FALHA_F (K INTEGER)
                RETURNS INTEGER
                AS
                BEGIN
                  INSERT INTO CH_LOG VALUES (:K, 'função antes');
                  RETURN 10 / K;
                END""", """
                CREATE OR ALTER PROCEDURE CH_NN (X INTEGER NOT NULL)
                RETURNS (Y INTEGER)
                AS
                BEGIN
                  Y = X;
                END""", """
                CREATE OR ALTER PROCEDURE CH_TRATA (K INTEGER)
                RETURNS (R INTEGER, CODIGO INTEGER, R2 INTEGER, CODIGO2 INTEGER, CODIGO3 INTEGER)
                AS
                DECLARE Y INTEGER;
                BEGIN
                  INSERT INTO CH_LOG VALUES (:K, 'trata antes');
                  BEGIN
                    INSERT INTO CH_LOG VALUES (:K, 'trata bloco');
                    EXECUTE PROCEDURE CH_FALHA (:K) RETURNING_VALUES :R;
                    WHEN ANY DO CODIGO = GDSCODE;
                  END
                  BEGIN
                    R2 = CH_FALHA_F(K);
                    WHEN ANY DO CODIGO2 = GDSCODE;
                  END
                  BEGIN
                    EXECUTE PROCEDURE CH_NN (NULLIF(K, 0)) RETURNING_VALUES :Y;
                    WHEN ANY DO CODIGO3 = GDSCODE;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE CH_CORPO_FALHA (K INTEGER)
                RETURNS (TOTAL INTEGER, CODIGO INTEGER)
                AS
                DECLARE I INTEGER;
                DECLARE Q NUMERIC(9,2);
                BEGIN
                  TOTAL = 0;
                  BEGIN
                    FOR SELECT I, Q FROM CH_C(3) INTO :I, :Q DO
                    BEGIN
                      INSERT INTO CH_LOG VALUES (:I, 'corpo ' || :I);
                      TOTAL = TOTAL + 10 / (K - I);
                    END
                    WHEN ANY DO CODIGO = GDSCODE;
                  END
                  INSERT INTO CH_LOG VALUES (:K, 'depois do laço');
                END""", """
                CREATE OR ALTER PROCEDURE CH_GERA_ERRO (K INTEGER)
                RETURNS (I INTEGER)
                AS
                BEGIN
                  I = 0;
                  WHILE (I < 3) DO
                  BEGIN
                    I = I + 1;
                    INSERT INTO CH_LOG VALUES (:I, 'gera ' || :I);
                    IF (I = K) THEN I = I / 0;
                    SUSPEND;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE CH_CONSOME (K INTEGER)
                RETURNS (TRILHA VARCHAR(100), CODIGO INTEGER)
                AS
                DECLARE I INTEGER;
                BEGIN
                  TRILHA = '';
                  BEGIN
                    FOR SELECT I FROM CH_GERA_ERRO(:K) INTO :I DO
                    BEGIN
                      INSERT INTO CH_LOG VALUES (:I, 'consome ' || :I);
                      TRILHA = TRILHA || I;
                    END
                    WHEN ANY DO CODIGO = GDSCODE;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE CH_SAI (K INTEGER)
                RETURNS (TRILHA VARCHAR(100))
                AS
                DECLARE I INTEGER;
                BEGIN
                  TRILHA = '';
                  FOR SELECT * FROM CH_C(5) AS CURSOR CUR DO
                  BEGIN
                    TRILHA = TRILHA || CUR.I || '=' || CUR.Q || ';';
                    IF (CUR.I = K) THEN EXIT;
                  END
                  TRILHA = TRILHA || 'fim';
                END""", """
                CREATE OR ALTER PROCEDURE CH_SEL (N INTEGER)
                RETURNS (I INTEGER, VALOR NUMERIC(9,2))
                AS
                BEGIN
                  FOR SELECT Q AS VALOR, I FROM CH_C(:N) AS CURSOR C DO
                  BEGIN
                    I = C.I * 10;
                    VALOR = C.VALOR;
                    INSERT INTO CH_LOG VALUES (:I, 'sel ' || :I);
                    SUSPEND;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE CH_DUPLA (N INTEGER, PARA INTEGER)
                RETURNS (I INTEGER, VALOR NUMERIC(9,2))
                AS
                BEGIN
                  FOR SELECT I, VALOR FROM CH_SEL(:N) INTO :I, :VALOR DO
                  BEGIN
                    IF (I = PARA) THEN LEAVE;
                    SUSPEND;
                  END
                  I = -1;
                  SUSPEND;
                END""", """
                CREATE OR ALTER PROCEDURE CH_PRIMEIRA (N INTEGER)
                RETURNS (I INTEGER, Q NUMERIC(9,2))
                AS
                BEGIN
                  EXECUTE PROCEDURE CH_C(:N) RETURNING_VALUES :I, :Q; -- selecionável: devolve o primeiro SUSPEND
                END""", """
                CREATE OR ALTER FUNCTION TRIPLO (X INTEGER)
                RETURNS INTEGER
                AS
                BEGIN
                  RETURN X * 100; -- fora da package: CH_PKG.TRIPLO sem package não pode cair aqui
                END""", """
                CREATE OR ALTER PACKAGE CH_PKG
                AS
                BEGIN
                  PROCEDURE ENTRADA (N INTEGER) RETURNS (R INTEGER, T VARCHAR(50), P INTEGER);
                  PROCEDURE PUBLICA (N INTEGER) RETURNS (R INTEGER);
                  FUNCTION TRIPLO (X INTEGER) RETURNS INTEGER;
                END""", """
                RECREATE PACKAGE BODY CH_PKG
                AS
                BEGIN
                  FUNCTION TRIPLO (X INTEGER) RETURNS INTEGER
                  AS
                  BEGIN
                    RETURN X * 3;
                  END

                  PROCEDURE PUBLICA (N INTEGER) RETURNS (R INTEGER)
                  AS
                  BEGIN
                    R = TRIPLO(N) + 1;
                  END

                  PROCEDURE PRIVADA (N INTEGER) RETURNS (R INTEGER)
                  AS
                  BEGIN
                    R = TRIPLO(N) - 1;
                  END

                  PROCEDURE ENTRADA (N INTEGER) RETURNS (R INTEGER, T VARCHAR(50), P INTEGER)
                  AS
                  DECLARE X INTEGER;
                  BEGIN
                    EXECUTE PROCEDURE PUBLICA (:N) RETURNING_VALUES :R;
                    X = TRIPLO(N);
                    T = 'x' || TRIPLO(X) || ',' || CH_DOBRO(1);
                    IF (N > 0) THEN EXECUTE PROCEDURE PRIVADA (:N) RETURNING_VALUES :P;
                  END
                END""");
        harness = new DifferentialHarness(database).tables("CH_LOG");
    }

    @Test
    void chainOfThreeRoutinesWithEachCallInterpretedOrOnTheServer() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            DifferentialHarness calls = harness.calls(policy);
            calls.assertSameOutputs("CH_A", 3, 99);
            calls.assertSameOutputs("CH_A", 3, 2); // LEAVE no meio do FOR SELECT sobre CH_C
            calls.assertSameOutputs("CH_A", 0, 99); // CH_C sem linhas
        }
    }

    @Test
    void errorInsideTheCalledRoutineIsUndoneAndHandledByTheCaller() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            DifferentialHarness calls = harness.calls(policy);
            calls.assertSameOutputs("CH_TRATA", 0);
            calls.assertSameOutputs("CH_TRATA", 2);
        }
    }

    @Test
    void changesOfTheCalledRoutineCountAsChangesOfTheCaller() throws SQLException {
        harness.calls(CallPolicy.INTERPRET).assertSameOutputs("CH_NIVEL_FOR", 1);
        harness.calls(CallPolicy.INTERPRET).assertSameOutputs("CH_NIVEL_FOR", 0);
        // No servidor não há como saber se a procedure alterou algo, e o Interpretador supõe que sim: com K = 0 o
        // FOR ganharia um nível que o motor não dá.
        harness.calls(CallPolicy.SERVER).assertSameOutputs("CH_NIVEL_FOR", 1);
    }

    @Test
    void errorInTheLoopBodyAbandonsTheSuspendedProcedure() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            DifferentialHarness calls = harness.calls(policy);
            calls.assertSameOutputs("CH_CORPO_FALHA", 2);
            calls.assertSameOutputs("CH_CORPO_FALHA", 9);
        }
    }

    @Test
    void errorInsideTheSuspendedProcedureReachesTheLoop() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            DifferentialHarness calls = harness.calls(policy);
            calls.assertSameOutputs("CH_CONSOME", 1);
            calls.assertSameOutputs("CH_CONSOME", 3);
            calls.assertSameOutputs("CH_CONSOME", 0);
        }
    }

    @Test
    void exitFromTheLoopBodyAndNamedCursorOverAnInterpretedProcedure() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            DifferentialHarness calls = harness.calls(policy);
            calls.assertSameOutputs("CH_SAI", 2);
            calls.assertSameOutputs("CH_SAI", 0);
        }
    }

    @Test
    void selectableChainLeavingThroughTwoSuspendedProcedures() throws SQLException {
        for (CallPolicy policy : List.of(CallPolicy.INTERPRET, CallPolicy.SERVER, allBut("CH_SEL"),
                allBut("CH_C"))) {
            DifferentialHarness calls = harness.calls(policy);
            calls.assertSameRows("CH_DUPLA", 3, 99);
            calls.assertSameRows("CH_DUPLA", 3, 20);
        }
    }

    @Test
    void executeProcedureOfASelectableReturnsTheFirstSuspend() throws SQLException {
        for (CallPolicy policy : POLICIES) {
            harness.calls(policy).assertSameOutputs("CH_PRIMEIRA", 3);
            harness.calls(policy).assertSameOutputs("CH_PRIMEIRA", 0);
        }
    }

    @Test
    void callsInsideAPackageReachTheRoutinesOfThePackage() throws SQLException {
        // Com N > 0, ENTRADA chama a PRIVADA, que de fora da package não existe: mesmo no step over ela é
        // interpretada.
        for (CallPolicy policy : List.of(CallPolicy.INTERPRET, CallPolicy.SERVER, allBut("PUBLICA"))) {
            harness.calls(policy).assertSamePackageOutputs("CH_PKG", "ENTRADA", 2);
            harness.calls(policy).assertSamePackageOutputs("CH_PKG", "ENTRADA", 0);
        }
    }

    @Test
    void framesShowTheCallChainAndHideTheSuspendedProcedure() throws Exception {
        List<String> stacks = new ArrayList<>();
        try (Connection connection = TestDatabase.get().connect()) {
            connection.setAutoCommit(false);
            Interpreter[] interpreter = new Interpreter[1];
            interpreter[0] = new Interpreter(connection, new DatabaseRoutineCatalog(connection), (call, caller) -> {
                StringBuilder stack = new StringBuilder(call.displayName() + " ←");
                for (Frame frame : interpreter[0].frames()) {
                    stack.append(' ').append(frame.routine().displayName()).append(':')
                            .append(frame.position().line());
                }
                // Valor da entrada N de quem chama, como a view Variables mostraria: já convertido para INTEGER.
                caller.find("N").ifPresent(n -> stack.append(" N=").append(n.value()));
                stacks.add(stack.toString());
                return true;
            }, new TemporaryPackages(connection));
            RoutineDefinition root = RoutineParser.parse(RoutineSourceLoader.loadProcedure(connection, "CH_A"));
            interpreter[0].execute(root, List.of(2, 99));
            connection.rollback();
        }
        // Linhas da Fonte do banco (cabeçalho como o editor do DBeaver mostra, uma saída por linha): CH_A chama CH_B
        // na linha 11, CH_B percorre CH_C na linha 13 e chama CH_DOBRO na linha 16, com CH_C parada no SUSPEND, fora
        // da pilha. CH_A passa N + 0.4 para CH_B, que recebe 2.
        assertEquals(List.of(
                "CH_B ← CH_A:11 N=2",
                "CH_C ← CH_B:13 CH_A:11 N=2",
                "CH_DOBRO ← CH_B:16 CH_A:11 N=2",
                "CH_DOBRO ← CH_B:16 CH_A:11 N=2",
                "CH_DOBRO ← CH_A:13 N=2",
                "COALESCE ← CH_A:14 N=2",
                "CH_B ← CH_A:15 N=2",
                "CH_C ← CH_B:13 CH_A:15 N=1",
                "CH_DOBRO ← CH_B:16 CH_A:15 N=1"), stacks);
    }
}
