package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Breakpoints, step over e pedido de pausa (issue 14). As linhas esperadas são achadas pelo comentário {@code -- tag}
 * no fim de cada linha da Fonte do banco.
 */
class ExecutionControlTest {

    private static TestDatabase database;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute(
                "CREATE TABLE EC_LOG (N INTEGER)", """
                CREATE OR ALTER PROCEDURE EC_FLUXO (N INTEGER)
                RETURNS (TOTAL INTEGER)
                AS
                  DECLARE I INTEGER = 0;
                BEGIN
                  TOTAL = 0; -- inicio
                  WHILE (I < N) DO -- laco
                  BEGIN
                    I = I + 1; -- incremento
                    IF (MOD(I, 2) = 0) THEN -- teste
                      TOTAL = TOTAL + I; -- par
                    ELSE
                      TOTAL = TOTAL - 1; -- impar
                  END
                  TOTAL = TOTAL * 10; -- fim
                END""", """
                CREATE OR ALTER PROCEDURE EC_LINHA
                RETURNS (Y INTEGER)
                AS
                  DECLARE X INTEGER = 0;
                BEGIN
                  Y = 0;
                  WHILE (X < 3) DO X = X + 1; -- uma
                  IF (X > 0) THEN Y = Y + 1; -- duas
                END""", """
                CREATE OR ALTER PROCEDURE EC_GERA (K INTEGER)
                RETURNS (V INTEGER)
                AS
                BEGIN
                  V = 0;
                  WHILE (V < K) DO
                  BEGIN
                    V = V + 1; -- gera
                    SUSPEND;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE EC_SOMA
                RETURNS (S INTEGER)
                AS
                  DECLARE V INTEGER;
                BEGIN
                  S = 0; -- antes
                  FOR SELECT V FROM EC_GERA(2) INTO :V DO -- para
                    S = S + V; -- corpo
                  S = S * 100; -- depois
                END""", """
                CREATE OR ALTER PROCEDURE EC_ERRO
                RETURNS (R INTEGER)
                AS
                BEGIN
                  R = 0;
                  BEGIN
                    R = 1 / R; -- divide
                    R = 5;
                  WHEN ANY DO
                    R = -1; -- trata
                  END
                  R = R * 2; -- depois
                END""", """
                CREATE OR ALTER PROCEDURE EC_LONGO
                AS
                  DECLARE N INTEGER = 0;
                BEGIN
                  WHILE (N < 1000000) DO
                  BEGIN
                    INSERT INTO EC_LOG VALUES (:N);
                    N = N + 1;
                  END
                END""");
    }

    @BeforeEach
    void clearLog() throws SQLException {
        database.execute("DELETE FROM EC_LOG");
    }

    @Test
    void breakpointsStopInExecutionOrder() throws Exception {
        Source fluxo = load("EC_FLUXO");
        Debugging debugging = new Debugging(Map.of("EC_FLUXO", Set.of(fluxo.line("teste"), fluxo.line("fim"))),
                false);

        List<Object> outputs = debugging.run(fluxo.routine(), List.of(3));

        assertEquals(List.of(fluxo.line("teste"), fluxo.line("teste"), fluxo.line("teste"), fluxo.line("fim")),
                debugging.lines());
        assertEquals(List.of(1, 2, 3, 3), debugging.values("I"));
        assertTrue(debugging.stops.stream().allMatch(stop -> stop.reason() == ExecutionControl.Reason.BREAKPOINT));
        assertEquals(List.of(0), outputs);
    }

    @Test
    void stepOverGoesStatementByStatementThroughIfAndWhile() throws Exception {
        Source fluxo = load("EC_FLUXO");
        Debugging debugging = new Debugging(Map.of("EC_FLUXO", Set.of(fluxo.line("inicio"))), true);

        List<Object> outputs = debugging.run(fluxo.routine(), List.of(2));

        // A cada volta, o step over passa de novo pelo WHILE, onde a condição é avaliada.
        assertEquals(fluxo.lines("inicio", "laco", "incremento", "teste", "impar", "laco", "incremento", "teste",
                "par", "laco", "fim"), debugging.lines());
        assertEquals(ExecutionControl.Reason.BREAKPOINT, debugging.stops.get(0).reason());
        assertTrue(debugging.stops.stream().skip(1)
                .allMatch(stop -> stop.reason() == ExecutionControl.Reason.STEP));
        assertEquals(List.of(10), outputs);
    }

    @Test
    void breakpointStopsOnceWhenEnteringTheLineAndAgainWhenALoopComesBack() throws Exception {
        Source linha = load("EC_LINHA");
        Debugging debugging = new Debugging(Map.of("EC_LINHA", Set.of(linha.line("uma"), linha.line("duas"))),
                false);

        debugging.run(linha.routine(), List.of());

        // O WHILE para a cada avaliação da condição; o corpo, na mesma linha, não. O IF para, a atribuição não.
        assertEquals(linha.lines("uma", "uma", "uma", "uma", "duas"), debugging.lines());
        assertEquals(List.of(0, 1, 2, 3, 3), debugging.values("X"));
    }

    @Test
    void stepOverStopsAtTheNextStatementOfTheSameLine() throws Exception {
        Source linha = load("EC_LINHA");
        Debugging debugging = new Debugging(Map.of("EC_LINHA", Set.of(linha.line("duas"))), true);

        debugging.run(linha.routine(), List.of());

        assertEquals(linha.lines("duas", "duas"), debugging.lines());
        assertEquals(ExecutionControl.Reason.STEP, debugging.stops.get(1).reason());
        assertTrue(debugging.stops.get(1).column() > debugging.stops.get(0).column());
    }

    @Test
    void neverStopsInsideARoutineTheDebuggerDoesNotFollow() throws Exception {
        Source soma = load("EC_SOMA");
        Source gera = load("EC_GERA");
        // A procedure do FOR SELECT é interpretada mesmo no step over, mas a camada de debug não para nela.
        Debugging debugging = new Debugging(
                Map.of("EC_SOMA", Set.of(soma.line("antes")), "EC_GERA", Set.of(gera.line("gera"))), true);

        List<Object> outputs = debugging.run(soma.routine(), List.of());

        assertEquals(soma.lines("antes", "para", "corpo", "para", "corpo", "para", "depois"), debugging.lines());
        assertEquals(List.of("EC_SOMA"), debugging.stops.stream().map(Stop::routine).distinct().toList());
        // No corpo do laço, a procedure parada no SUSPEND não está na pilha.
        assertEquals(1, debugging.stops.get(2).depth());
        assertEquals(List.of(300), outputs);
    }

    @Test
    void stepOverFollowsAnErrorIntoTheHandler() throws Exception {
        Source erro = load("EC_ERRO");
        Debugging debugging = new Debugging(Map.of("EC_ERRO", Set.of(erro.line("divide"))), true);

        List<Object> outputs = debugging.run(erro.routine(), List.of());

        assertEquals(erro.lines("divide", "trata", "depois"), debugging.lines());
        assertEquals(List.of(-2), outputs);
    }

    @Test
    void pauseRequestStopsARunningLoopAndTerminateRollsBack() throws Exception {
        RoutineDefinition routine = load("EC_LONGO").routine();
        CountDownLatch paused = new CountDownLatch(1);
        AtomicReference<ExecutionControl.Pause> pause = new AtomicReference<>();
        AtomicReference<DebugSession> session = new AtomicReference<>();
        ExecutionControl control = new ExecutionControl(ExecutionControl.Breakpoints.NONE, stop -> {
            pause.set(stop);
            paused.countDown();
        }, () -> session.get().frames());
        // Com "commit ao terminar": o Terminate faz rollback mesmo assim.
        try (DebugSession opened = new DebugSession(database.connect(), DebugSession.Isolation.READ_COMMITTED, true,
                CallPolicy.SERVER, control)) {
            session.set(opened);
            CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
                try {
                    opened.run(routine, List.of());
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            control.suspend();
            assertTrue(paused.await(30, TimeUnit.SECONDS));
            assertEquals(ExecutionControl.Reason.REQUEST, pause.get().reason());
            assertTrue(control.isPaused());

            opened.abort();
            control.release();
            assertInstanceOf(SessionAbortedException.class, outcome.get(30, TimeUnit.SECONDS));
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM EC_LOG"));
    }

    /** Uma pausa: onde, por quê, a profundidade da pilha e os valores das variáveis do frame de cima. */
    private record Stop(ExecutionControl.Reason reason, String routine, int line, int column, int depth,
            Map<String, Object> values) {
    }

    /** Sessão de debug em que cada pausa é registrada e seguida de resume ou de step over. */
    private static final class Debugging {

        final List<Stop> stops = new ArrayList<>();
        private final Map<String, Set<Integer>> breakpoints;
        private final boolean stepping;

        /** @param breakpoints linhas com breakpoint, por nome da Rotina */
        Debugging(Map<String, Set<Integer>> breakpoints, boolean stepping) {
            this.breakpoints = breakpoints;
            this.stepping = stepping;
        }

        /** @return as saídas da Rotina */
        List<Object> run(RoutineDefinition routine, List<?> inputs) throws SQLException {
            AtomicReference<DebugSession> session = new AtomicReference<>();
            AtomicReference<ExecutionControl> control = new AtomicReference<>();
            control.set(new ExecutionControl(
                    (source, line) -> breakpoints.getOrDefault(source.name(), Set.of()).contains(line) ? line : null,
                    pause -> {
                        Frame top = pause.frames().get(0);
                        Map<String, Object> values = new HashMap<>();
                        top.variables().forEach(variable -> values.put(variable.name(), variable.value()));
                        stops.add(new Stop(pause.reason(), top.routine().name(), top.position().line(),
                                top.position().column(), pause.frames().size(), values));
                        if (stepping) {
                            control.get().stepOver();
                        } else {
                            control.get().resume();
                        }
                    }, () -> session.get().frames()));
            try (DebugSession opened = new DebugSession(database.connect(), DebugSession.Isolation.READ_COMMITTED,
                    false, CallPolicy.SERVER, control.get())) {
                session.set(opened);
                return opened.run(routine, inputs).values();
            }
        }

        List<Integer> lines() {
            return stops.stream().map(Stop::line).toList();
        }

        List<Object> values(String variable) {
            return stops.stream().map(stop -> stop.values().get(variable)).toList();
        }
    }

    /** Fonte do banco de uma procedure e o AST dela. */
    private record Source(String text, RoutineDefinition routine) {

        /** Linha que termina com {@code -- tag}. */
        int line(String tag) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].stripTrailing().endsWith("-- " + tag)) {
                    return i + 1;
                }
            }
            throw new IllegalArgumentException("sem a tag " + tag);
        }

        List<Integer> lines(String... tags) {
            List<Integer> lines = new ArrayList<>();
            for (String tag : tags) {
                lines.add(line(tag));
            }
            return lines;
        }
    }

    private static Source load(String procedure) throws SQLException {
        try (Connection connection = database.connect()) {
            String text = RoutineSourceLoader.loadProcedure(connection, procedure);
            return new Source(text, RoutineParser.parse(text));
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
