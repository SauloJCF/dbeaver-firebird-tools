package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Step into, step return e breakpoints nas Rotinas chamadas (issue 17), com o {@link ExecutionControl} como política
 * das chamadas, como no launch. As linhas são achadas pelo comentário {@code -- tag} no fim de cada linha da Fonte do
 * banco.
 */
class StepIntoTest {

    private static TestDatabase database;
    /** Linha de cada tag, por Rotina. */
    private static final Map<String, Map<String, Integer>> TAGS = new HashMap<>();

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute("CREATE TABLE SI_LOG (ORIGEM VARCHAR(10), V INTEGER)", """
                CREATE OR ALTER PROCEDURE SI_C (X INTEGER)
                RETURNS (Y INTEGER)
                AS
                BEGIN
                  Y = X * 10; -- c1
                  Y = Y + 1; -- c2
                END""", """
                CREATE OR ALTER PROCEDURE SI_B (X INTEGER)
                RETURNS (Y INTEGER)
                AS
                  DECLARE T INTEGER;
                BEGIN
                  T = X + 1; -- b1
                  EXECUTE PROCEDURE SI_C(:T) RETURNING_VALUES :Y; -- chama_c
                  Y = Y + 100; -- depois_c
                END""", """
                CREATE OR ALTER FUNCTION SI_F (X INTEGER) RETURNS INTEGER
                AS
                BEGIN
                  RETURN X * 2; -- f1
                END""", """
                CREATE OR ALTER PROCEDURE SI_A (X INTEGER)
                RETURNS (R INTEGER, S INTEGER)
                AS
                BEGIN
                  EXECUTE PROCEDURE SI_B(:X) RETURNING_VALUES :R; -- chama_b
                  S = SI_F(R); -- chama_f
                  INSERT INTO SI_LOG SELECT 'A', SI_F(:S) FROM RDB$DATABASE; -- dml
                  S = S + SI_F(1); -- expressao
                  R = R + 1; -- fim
                END""", """
                CREATE OR ALTER PROCEDURE SI_SUB (X INTEGER)
                RETURNS (R INTEGER)
                AS
                  DECLARE FUNCTION DOBRO (V INTEGER) RETURNS INTEGER
                  AS
                  BEGIN
                    RETURN V * 2; -- dobro
                  END
                  DECLARE PROCEDURE SOMA_UM (V INTEGER) RETURNS (W INTEGER)
                  AS
                  BEGIN
                    W = V + 1; -- soma_um
                  END
                BEGIN
                  R = DOBRO(X); -- chama_dobro
                  EXECUTE PROCEDURE SOMA_UM(:R) RETURNING_VALUES :R; -- chama_soma
                  R = R * 10; -- fim
                END""", """
                CREATE OR ALTER PROCEDURE SI_GERA (K INTEGER)
                RETURNS (V INTEGER)
                AS
                BEGIN
                  V = 0; -- gera_inicio
                  WHILE (V < K) DO -- gera_laco
                  BEGIN
                    V = V + 1; -- gera
                    SUSPEND; -- suspende
                  END
                END""", """
                CREATE OR ALTER PROCEDURE SI_SOMA
                RETURNS (S INTEGER)
                AS
                  DECLARE V INTEGER;
                BEGIN
                  S = 0; -- antes
                  FOR SELECT V FROM SI_GERA(2) INTO :V DO -- para
                    S = S + V; -- corpo
                  S = S * 100; -- depois
                END""");
        try (Connection connection = database.connect()) {
            for (String procedure : List.of("SI_A", "SI_B", "SI_C", "SI_SUB", "SI_GERA", "SI_SOMA")) {
                tags(procedure, RoutineSourceLoader.loadProcedure(connection, procedure));
            }
            tags("SI_F", RoutineSourceLoader.loadFunction(connection, "SI_F"));
        }
    }

    @Test
    void stepIntoGoesDownTheChainAndStepReturnComesBackAfterTheCall() throws Exception {
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_A:chama_b")) {
            driven.expect("SI_A", "chama_b", 1).stepInto();
            driven.expect("SI_B", "b1", 2).stepOver();
            driven.expect("SI_B", "chama_c", 2).stepInto();
            List<Frame> frames = driven.expect("SI_C", "c1", 3).frames();
            assertEquals(List.of("SI_C", "SI_B", "SI_A"), frames.stream().map(f -> f.routine().name()).toList());
            driven.control.stepReturn(); // do primeiro statement: o resto de SI_C roda sem parar

            // De volta a SI_B, na linha seguinte à chamada, com a saída de SI_C já no alvo do RETURNING_VALUES.
            Frame b = driven.expect("SI_B", "depois_c", 2).frames().get(0);
            assertEquals(21, b.find("Y").orElseThrow().value());
            driven.control.stepReturn();

            Frame a = driven.expect("SI_A", "chama_f", 1).frames().get(0);
            assertEquals(121, a.find("R").orElseThrow().value());
            driven.control.resume();
            assertEquals(List.of(122, 244), driven.finish());
        }
    }

    @Test
    void stepIntoAFunctionAssignmentAndBack() throws Exception {
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_A:chama_f")) {
            driven.expect("SI_A", "chama_f", 1).stepInto();
            Frame f = driven.expect("SI_F", "f1", 2).frames().get(0);
            assertEquals(121, f.find("X").orElseThrow().value());
            driven.control.stepReturn();

            Frame a = driven.expect("SI_A", "dml", 1).frames().get(0);
            assertEquals(242, a.find("S").orElseThrow().value());
            driven.control.resume();
            assertEquals(List.of(122, 244), driven.finish());
        }
    }

    @Test
    void stepIntoACallExecutedOnTheServerIsAStepOver() throws Exception {
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_A:dml")) {
            driven.expect("SI_A", "dml", 1);
            // O breakpoint em SI_F não vale nas chamadas dentro da Fatia DML e da expressão: elas rodam no servidor.
            driven.breakpoint("SI_F", "f1");
            driven.stepInto();
            driven.expect("SI_A", "expressao", 1).stepInto();
            driven.expect("SI_A", "fim", 1).resume();
            assertEquals(List.of(122, 244), driven.finish());
            assertNull(driven.pauses.poll());
        }
    }

    @Test
    void stepOverStopsAtABreakpointInsideTheCalledRoutine() throws Exception {
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_A:chama_b", "SI_C:c2")) {
            driven.expect("SI_A", "chama_b", 1).stepOver();
            ExecutionControl.Pause pause = driven.nextPause();
            assertEquals(ExecutionControl.Reason.BREAKPOINT, pause.reason());
            driven.expect(pause, "SI_C", "c2", 3);
            driven.control.stepOver();
            // O step over continua valendo para o frame em que a execução parou, agora o de SI_C.
            driven.expect("SI_B", "depois_c", 2).resume();
            assertEquals(List.of(122, 244), driven.finish());
        }
    }

    @Test
    void breakpointInARoutineCalledByACalledRoutineStopsOnResume() throws Exception {
        // Nenhum breakpoint em SI_A nem em SI_B: as duas chamadas são interpretadas porque SI_C tem um.
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_C:c1")) {
            List<Frame> frames = driven.expect("SI_C", "c1", 3).frames();
            assertEquals(2, frames.get(0).find("X").orElseThrow().value());
            driven.control.resume();
            assertEquals(List.of(122, 244), driven.finish());
            assertNull(driven.pauses.poll());
        }
    }

    @Test
    void stepOverFromACallerFrameStopsAtItsNextStatement() throws Exception {
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_C:c1")) {
            List<Frame> frames = driven.expect("SI_C", "c1", 3).frames();
            driven.control.stepOver(frames.get(1));
            driven.expect("SI_B", "depois_c", 2).resume();
            assertEquals(List.of(122, 244), driven.finish());
        }
    }

    @Test
    void stepReturnFromACallerFrameStopsInWhoCalledIt() throws Exception {
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_C:c1")) {
            List<Frame> frames = driven.expect("SI_C", "c1", 3).frames();
            driven.control.stepReturn(frames.get(1));
            driven.expect("SI_A", "chama_f", 1).resume();
            assertEquals(List.of(122, 244), driven.finish());
        }
    }

    @Test
    void stepReturnFromTheRootRunsToTheNextBreakpointOrTheEnd() throws Exception {
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_A:chama_f", "SI_A:fim")) {
            driven.expect("SI_A", "chama_f", 1).stepReturn();
            ExecutionControl.Pause pause = driven.nextPause();
            assertEquals(ExecutionControl.Reason.BREAKPOINT, pause.reason());
            driven.expect(pause, "SI_A", "fim", 1);
            driven.control.stepReturn();
            assertEquals(List.of(122, 244), driven.finish());
        }
    }

    @Test
    void theCallerFramesKeepTheirOwnVariables() throws Exception {
        try (Driven driven = new Driven("SI_A", List.of(1), "SI_C:c2")) {
            List<Frame> frames = driven.expect("SI_C", "c2", 3).frames();

            assertEquals(Map.of("X", 2, "Y", 20), values(frames.get(0)));
            Map<String, Object> b = values(frames.get(1));
            assertEquals(1, b.get("X"));
            assertEquals(2, b.get("T"));
            assertNull(b.get("Y"));
            assertEquals(1, values(frames.get(2)).get("X"));
            // A linha de quem chamou é a da chamada.
            assertEquals(TAGS.get("SI_B").get("chama_c"), frames.get(1).position().line());
            assertEquals(6L, driven.watch(frames.get(1), "T * 3"));

            driven.control.resume();
            assertEquals(List.of(122, 244), driven.finish());
        }
    }

    @Test
    void stepIntoASubroutineStaysInTheTextOfItsRoutine() throws Exception {
        try (Driven driven = new Driven("SI_SUB", List.of(3), "SI_SUB:chama_dobro")) {
            driven.expect("SI_SUB", "chama_dobro", 1).stepInto();
            Frame dobro = driven.expect("SI_SUB", "dobro", 2).frames().get(0);
            assertEquals("DOBRO", dobro.routine().name());
            assertEquals("SI_SUB", dobro.sourceRoutine().name());
            driven.control.stepReturn();

            driven.expect("SI_SUB", "chama_soma", 1).stepInto();
            Frame somaUm = driven.expect("SI_SUB", "soma_um", 2).frames().get(0);
            assertEquals("SOMA_UM", somaUm.routine().name());
            assertEquals(6, somaUm.find("V").orElseThrow().value());
            driven.control.stepOver(); // o fim da sub-rotina: volta a quem a chamou

            driven.expect("SI_SUB", "fim", 1).resume();
            assertEquals(List.of(70), driven.finish());
        }
    }

    @Test
    void stepOverPassesASubroutineAndItsBreakpointStopsOnResume() throws Exception {
        try (Driven driven = new Driven("SI_SUB", List.of(3), "SI_SUB:chama_dobro")) {
            driven.expect("SI_SUB", "chama_dobro", 1).stepOver();
            driven.expect("SI_SUB", "chama_soma", 1).resume();
            assertEquals(List.of(70), driven.finish());
        }
        try (Driven driven = new Driven("SI_SUB", List.of(3), "SI_SUB:soma_um")) {
            assertEquals("SOMA_UM", driven.expect("SI_SUB", "soma_um", 2).frames().get(0).routine().name());
            driven.control.resume();
            assertEquals(List.of(70), driven.finish());
        }
    }

    @Test
    void stepIntoTheForSelectEntersTheProcedureAndComesBackAtEachSuspend() throws Exception {
        try (Driven driven = new Driven("SI_SOMA", List.of(), "SI_SOMA:antes")) {
            driven.expect("SI_SOMA", "antes", 1).stepOver();
            driven.expect("SI_SOMA", "para", 1).stepInto();
            driven.expect("SI_GERA", "gera_inicio", 2).stepOver();
            driven.expect("SI_GERA", "gera_laco", 2).stepOver();
            driven.expect("SI_GERA", "gera", 2).stepOver();
            driven.expect("SI_GERA", "suspende", 2).stepOver();
            // O SUSPEND entrega a linha ao corpo do laço, com a procedure parada fora da pilha.
            driven.expect("SI_SOMA", "corpo", 1).stepOver();
            driven.expect("SI_SOMA", "para", 1).stepInto();
            // A procedure continua depois do SUSPEND: o laço dela volta à linha do WHILE.
            driven.expect("SI_GERA", "gera_laco", 2).resume();
            assertEquals(List.of(300), driven.finish());
        }
    }

    @Test
    void stepIntoInALaterTurnOfTheForSelectFollowsTheProcedure() throws Exception {
        try (Driven driven = new Driven("SI_SOMA", List.of(), "SI_SOMA:antes")) {
            driven.expect("SI_SOMA", "antes", 1).stepOver();
            driven.expect("SI_SOMA", "para", 1).stepOver();
            // No step over, a procedure é interpretada sem que a camada de debug pare nela.
            driven.expect("SI_SOMA", "corpo", 1).stepOver();
            driven.expect("SI_SOMA", "para", 1).stepInto();
            driven.expect("SI_GERA", "gera_laco", 2).stepOver();
            driven.expect("SI_GERA", "gera", 2).resume();
            assertEquals(List.of(300), driven.finish());
        }
    }

    @Test
    void breakpointPutInTheProcedureDuringTheForSelectStopsInTheNextTurn() throws Exception {
        try (Driven driven = new Driven("SI_SOMA", List.of(), "SI_SOMA:corpo")) {
            Frame soma = driven.expect("SI_SOMA", "corpo", 1).frames().get(0);
            assertEquals(1, soma.find("V").orElseThrow().value());
            driven.breakpoint("SI_GERA", "gera");
            driven.control.resume();
            Frame gera = driven.expect("SI_GERA", "gera", 2).frames().get(0);
            assertEquals(1, gera.find("V").orElseThrow().value()); // antes do V = V + 1 da segunda volta
            driven.control.resume();
            driven.expect("SI_SOMA", "corpo", 1).resume();
            assertEquals(List.of(300), driven.finish());
        }
    }

    private static Map<String, Object> values(Frame frame) {
        Map<String, Object> values = new HashMap<>();
        frame.variables().forEach(variable -> values.put(variable.name(), variable.value()));
        return values;
    }

    private static void tags(String routine, String text) {
        Map<String, Integer> lines = new HashMap<>();
        String[] all = text.split("\n", -1);
        for (int i = 0; i < all.length; i++) {
            String line = all[i].stripTrailing();
            int tag = line.lastIndexOf("-- ");
            if (tag >= 0) {
                lines.put(line.substring(tag + 3), i + 1);
            }
        }
        TAGS.put(routine, lines);
    }

    /**
     * Sessão rodando em outra thread, conduzida pelo teste a cada pausa, como a UI faria, com o controle como gancho e
     * como política das chamadas (como o launch).
     */
    private static final class Driven implements AutoCloseable {

        final ExecutionControl control;
        final BlockingQueue<ExecutionControl.Pause> pauses = new LinkedBlockingQueue<>();
        private final Set<String> breakpoints = ConcurrentHashMap.newKeySet();
        private final DebugSession session;
        private final CompletableFuture<List<Object>> result;
        /** A última pausa vista por {@link #expect}. */
        private ExecutionControl.Pause last;

        /** @param breakpoints {@code ROTINA:tag}, do texto da Rotina ({@link Frame#sourceRoutine()}) */
        Driven(String procedure, List<?> inputs, String... breakpoints) throws Exception {
            for (String breakpoint : breakpoints) {
                String[] parts = breakpoint.split(":");
                breakpoint(parts[0], parts[1]);
            }
            RoutineDefinition routine;
            try (Connection connection = database.connect()) {
                routine = RoutineParser.parse(RoutineSourceLoader.loadProcedure(connection, procedure));
            }
            AtomicReference<DebugSession> opened = new AtomicReference<>();
            control = new ExecutionControl(
                    (source, line) -> this.breakpoints.contains(source.name() + ":" + line) ? line : null,
                    pauses::add, () -> opened.get().frames());
            session = new DebugSession(database.connect(), DebugSession.Isolation.READ_COMMITTED, false, control,
                    control);
            opened.set(session);
            result = CompletableFuture.supplyAsync(() -> {
                try {
                    return session.run(routine, inputs).values();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        /** Põe um breakpoint, valendo a partir do próximo statement. */
        void breakpoint(String routine, String tag) {
            breakpoints.add(routine + ":" + line(routine, tag));
        }

        ExecutionControl.Pause nextPause() throws InterruptedException {
            ExecutionControl.Pause pause = pauses.poll(30, TimeUnit.SECONDS);
            assertNotNull(pause, "a execução não parou");
            return pause;
        }

        /** A próxima pausa, no texto e na linha dados, com a pilha dessa altura. */
        Driven expect(String routine, String tag, int depth) throws InterruptedException {
            expect(nextPause(), routine, tag, depth);
            return this;
        }

        void expect(ExecutionControl.Pause pause, String routine, String tag, int depth) {
            last = pause;
            Frame top = pause.frames().get(0);
            assertEquals(routine + ":" + tag + " (" + depth + ")", top.sourceRoutine().name() + ":"
                    + tagOf(top.sourceRoutine().name(), top.position().line()) + " (" + pause.frames().size() + ")");
        }

        /** Pilha da última pausa vista por {@link #expect}. */
        List<Frame> frames() {
            return last.frames();
        }

        void stepInto() {
            control.stepInto();
        }

        void stepOver() {
            control.stepOver();
        }

        void stepReturn() {
            control.stepReturn();
        }

        void resume() {
            control.resume();
        }

        Object watch(Frame frame, String text) throws Exception {
            return control.whilePaused(() -> session.watch(frame, text)).get(30, TimeUnit.SECONDS).value();
        }

        List<Object> finish() throws Exception {
            return result.get(30, TimeUnit.SECONDS);
        }

        private static int line(String routine, String tag) {
            Integer line = TAGS.get(routine).get(tag);
            assertNotNull(line, "sem a tag " + tag + " em " + routine);
            return line;
        }

        private static String tagOf(String routine, int line) {
            return TAGS.get(routine).entrySet().stream().filter(entry -> entry.getValue() == line)
                    .map(Map.Entry::getKey).findFirst().orElse("linha " + line);
        }

        @Override
        public void close() throws SQLException {
            control.release();
            result.handle((value, error) -> null).join();
            session.close();
        }
    }
}
