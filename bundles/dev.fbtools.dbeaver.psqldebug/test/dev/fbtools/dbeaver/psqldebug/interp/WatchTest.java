package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Watches avaliados durante a pausa (issue 16), como a view Expressions faz: de outra thread, pela tarefa que roda na
 * thread da execução parada.
 */
class WatchTest {

    private static TestDatabase database;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute("CREATE TABLE WT_LOG (ID INTEGER)", "CREATE SEQUENCE WT_SEQ", """
                CREATE OR ALTER PROCEDURE WT_PASSOS
                RETURNS (A INTEGER, B INTEGER)
                AS
                BEGIN
                  A = 1; -- inicio
                  B = 2;
                  A = A + 10;
                  B = B * 3;
                END""", """
                CREATE OR ALTER FUNCTION WT_GRAVA (V INTEGER) RETURNS INTEGER
                AS
                BEGIN
                  INSERT INTO WT_LOG (ID) VALUES (:V);
                  RETURN (SELECT COUNT(*) FROM WT_LOG);
                END""", """
                CREATE OR ALTER PROCEDURE WT_GRAVA_E_CONTA
                RETURNS (N INTEGER)
                AS
                BEGIN
                  INSERT INTO WT_LOG (ID) VALUES (1);
                  N = (SELECT COUNT(*) FROM WT_LOG); -- conta
                END""", """
                CREATE OR ALTER PROCEDURE WT_INTERNA (X INTEGER)
                RETURNS (Y INTEGER)
                AS
                BEGIN
                  Y = X * 2; -- interna
                END""", """
                CREATE OR ALTER PROCEDURE WT_EXTERNA
                RETURNS (R INTEGER)
                AS
                  DECLARE X INTEGER = 100;
                BEGIN
                  EXECUTE PROCEDURE WT_INTERNA(7) RETURNING_VALUES :R;
                END""");
    }

    @Test
    void watchFollowsTheVariablesAtEachStep() throws Exception {
        try (Driven driven = new Driven("WT_PASSOS", "WT_PASSOS", "inicio", CallPolicy.SERVER)) {
            List<Object> values = new ArrayList<>();
            for (int step = 0; step < 4; step++) {
                Frame top = driven.nextPause().frames().get(0);
                WatchValue watch = driven.watch(top, "a * b + 1").get(30, TimeUnit.SECONDS);
                assertEquals("BIGINT", watch.type());
                values.add(watch.value());
                driven.control.stepOver();
            }

            assertEquals(Arrays.asList(null, null, 3L, 23L), values);
            assertEquals(List.of(11, 6), driven.finish());
        }
    }

    @Test
    void invalidWatchFailsWithoutStoppingTheSession() throws Exception {
        try (Driven driven = new Driven("WT_PASSOS", "WT_PASSOS", "inicio", CallPolicy.SERVER)) {
            Frame top = driven.nextPause().frames().get(0);
            int line = top.position().line();

            assertInstanceOf(SQLException.class, failure(driven.watch(top, "A +")));
            assertInstanceOf(SQLException.class, failure(driven.watch(top, "NAO_EXISTE + 1")));
            assertInstanceOf(PsqlSyntaxException.class, failure(driven.watch(top, "A; B")));

            driven.control.stepOver();
            Frame next = driven.nextPause().frames().get(0);
            assertEquals(line + 1, next.position().line());
            assertEquals(1, driven.watch(next, "A").get(30, TimeUnit.SECONDS).value());
            driven.control.resume();
            assertEquals(List.of(11, 6), driven.finish());
        }
    }

    @Test
    void watchDoesNotChangeTheDatabase() throws Exception {
        try (Driven driven = new Driven("WT_GRAVA_E_CONTA", "WT_GRAVA_E_CONTA", "conta", CallPolicy.SERVER)) {
            Frame top = driven.nextPause().frames().get(0);

            // A function grava e conta a linha dela e a da procedure; depois do watch, só a da procedure fica.
            assertEquals(2, driven.watch(top, "WT_GRAVA(2)").get(30, TimeUnit.SECONDS).value());
            assertEquals(1L, driven.watch(top, "(SELECT COUNT(*) FROM WT_LOG)").get(30, TimeUnit.SECONDS).value());
            assertInstanceOf(PsqlSyntaxException.class, failure(driven.watch(top, "GEN_ID(WT_SEQ, 1)")));
            assertEquals(0L, driven.watch(top, "GEN_ID(WT_SEQ, 0)").get(30, TimeUnit.SECONDS).value());

            driven.control.resume();
            assertEquals(List.of(1), driven.finish());
        }
    }

    @Test
    void watchUsesTheVariablesOfTheGivenFrame() throws Exception {
        try (Driven driven = new Driven("WT_EXTERNA", "WT_INTERNA", "interna", CallPolicy.INTERPRET)) {
            List<Frame> frames = driven.nextPause().frames();

            assertEquals(7, driven.watch(frames.get(0), "X").get(30, TimeUnit.SECONDS).value());
            assertEquals(100, driven.watch(frames.get(1), "X").get(30, TimeUnit.SECONDS).value());

            driven.control.resume();
            assertEquals(List.of(14), driven.finish());
        }
    }

    private static Throwable failure(CompletableFuture<?> watch) {
        return assertThrows(ExecutionException.class, () -> watch.get(30, TimeUnit.SECONDS)).getCause();
    }

    /** Sessão rodando em outra thread, conduzida pelo teste a cada pausa, como a UI faria. */
    private static final class Driven implements AutoCloseable {

        final ExecutionControl control;
        private final DebugSession session;
        private final BlockingQueue<ExecutionControl.Pause> pauses = new LinkedBlockingQueue<>();
        private final CompletableFuture<List<Object>> result;

        /**
         * @param stopIn procedure do breakpoint
         * @param tag a linha do breakpoint em {@code stopIn}, pelo comentário {@code -- tag}
         */
        Driven(String procedure, String stopIn, String tag, CallPolicy policy) throws Exception {
            RoutineDefinition routine;
            int breakpoint;
            try (Connection connection = database.connect()) {
                routine = RoutineParser.parse(RoutineSourceLoader.loadProcedure(connection, procedure));
                breakpoint = line(RoutineSourceLoader.loadProcedure(connection, stopIn), tag);
            }
            AtomicReference<DebugSession> opened = new AtomicReference<>();
            control = new ExecutionControl(
                    (source, line) -> source.name().equals(stopIn) && line == breakpoint ? tag : null, pauses::add,
                    () -> opened.get().frames());
            session = new DebugSession(database.connect(), DebugSession.Isolation.READ_COMMITTED, false, policy,
                    control);
            opened.set(session);
            result = CompletableFuture.supplyAsync(() -> {
                try {
                    return session.run(routine, List.of()).values();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        ExecutionControl.Pause nextPause() throws InterruptedException {
            ExecutionControl.Pause pause = pauses.poll(30, TimeUnit.SECONDS);
            assertNotNull(pause, "a execução não parou");
            return pause;
        }

        CompletableFuture<WatchValue> watch(Frame frame, String text) {
            return control.whilePaused(() -> session.watch(frame, text));
        }

        List<Object> finish() throws Exception {
            return result.get(30, TimeUnit.SECONDS);
        }

        private static int line(String text, String tag) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].stripTrailing().endsWith("-- " + tag)) {
                    return i + 1;
                }
            }
            throw new IllegalArgumentException("sem a tag " + tag);
        }

        @Override
        public void close() throws SQLException {
            control.release();
            result.handle((value, error) -> null).join();
            session.close();
        }
    }
}
