package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
 * Variável editada durante a pausa (issue 15), como a view Variables faz: de outra thread, pela tarefa que roda na
 * thread da execução parada.
 */
class VariableEditTest {

    private static TestDatabase database;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute("""
                CREATE OR ALTER PROCEDURE VE_CAMINHO (A INTEGER)
                RETURNS (CAMINHO VARCHAR(10), DIA DATE)
                AS
                  DECLARE N INTEGER NOT NULL = 0;
                BEGIN
                  DIA = DATE '2000-01-01';
                  IF (A > 10) THEN -- teste
                    CAMINHO = 'grande'; -- grande
                  ELSE
                    CAMINHO = 'pequeno'; -- pequeno
                END""");
    }

    @Test
    void editedVariableChangesThePathOfTheNextStep() throws Exception {
        try (Driven driven = new Driven("VE_CAMINHO", List.of(1), "teste")) {
            Frame top = driven.nextPause().frames().get(0);

            driven.assign(top, "A", "50").get(30, TimeUnit.SECONDS);
            driven.control.stepOver();

            assertEquals(driven.line("grande"), driven.nextPause().frames().get(0).position().line());
            driven.control.resume();
            assertEquals("grande", driven.finish().get(0));
        }
    }

    @Test
    void invalidTextFailsAndKeepsTheVariable() throws Exception {
        try (Driven driven = new Driven("VE_CAMINHO", List.of(1), "teste")) {
            Frame top = driven.nextPause().frames().get(0);

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> driven.assign(top, "A", "abc").get(30, TimeUnit.SECONDS));
            assertInstanceOf(SQLException.class, error.getCause());
            assertEquals(1, top.find("A").orElseThrow().value());

            // NULL numa variável NOT NULL é recusado, como numa atribuição.
            error = assertThrows(ExecutionException.class,
                    () -> driven.assign(top, "N", " null ").get(30, TimeUnit.SECONDS));
            assertInstanceOf(SQLException.class, error.getCause());
            assertEquals(0, top.find("N").orElseThrow().value());

            driven.control.resume();
            assertEquals("pequeno", driven.finish().get(0));
        }
    }

    @Test
    void textIsConvertedByTheServerAndNullIsExplicit() throws Exception {
        try (Driven driven = new Driven("VE_CAMINHO", List.of(1), "teste")) {
            Frame top = driven.nextPause().frames().get(0);

            // Formatos que o CAST do servidor aceita, e o Jaybird sozinho não.
            driven.assign(top, "A", "10.5").get(30, TimeUnit.SECONDS);
            driven.assign(top, "DIA", "17.10.2026").get(30, TimeUnit.SECONDS);
            assertEquals(11, top.find("A").orElseThrow().value());
            assertEquals(LocalDate.of(2026, 10, 17),
                    ((java.sql.Date) top.find("DIA").orElseThrow().value()).toLocalDate());

            driven.assign(top, "DIA", "NULL").get(30, TimeUnit.SECONDS);
            driven.assign(top, "CAMINHO", "'NULL'").get(30, TimeUnit.SECONDS);
            assertNull(top.find("DIA").orElseThrow().value());
            assertEquals("'NULL'", top.find("CAMINHO").orElseThrow().value());

            driven.control.resume();
            List<Object> outputs = driven.finish();
            assertEquals("grande", outputs.get(0));
            assertNull(outputs.get(1));
        }
    }

    @Test
    void tasksAreRefusedWhenTheExecutionIsNotPaused() {
        ExecutionControl control = new ExecutionControl(ExecutionControl.Breakpoints.NONE, pause -> {
        }, List::of);

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> control.whilePaused(() -> null).get(30, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    /** Sessão rodando em outra thread, conduzida pelo teste a cada pausa, como a UI faria. */
    private static final class Driven implements AutoCloseable {

        final ExecutionControl control;
        private final String text;
        private final DebugSession session;
        private final BlockingQueue<ExecutionControl.Pause> pauses = new LinkedBlockingQueue<>();
        private final CompletableFuture<List<Object>> result;

        /** @param tag a linha do breakpoint, pelo comentário {@code -- tag} */
        Driven(String procedure, List<?> inputs, String tag) throws Exception {
            RoutineDefinition routine;
            try (Connection connection = database.connect()) {
                text = RoutineSourceLoader.loadProcedure(connection, procedure);
                routine = RoutineParser.parse(text);
            }
            int breakpoint = line(tag);
            AtomicReference<DebugSession> opened = new AtomicReference<>();
            control = new ExecutionControl((source, line) -> line == breakpoint ? tag : null, pauses::add,
                    () -> opened.get().frames());
            session = new DebugSession(database.connect(), DebugSession.Isolation.READ_COMMITTED, false,
                    CallPolicy.SERVER, control);
            opened.set(session);
            result = CompletableFuture.supplyAsync(() -> {
                try {
                    return session.run(routine, inputs).values();
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

        CompletableFuture<Void> assign(Frame frame, String variable, String value) {
            return control.whilePaused(() -> {
                session.assign(frame, frame.find(variable).orElseThrow(), value);
                return null;
            });
        }

        List<Object> finish() throws Exception {
            return result.get(30, TimeUnit.SECONDS);
        }

        int line(String tag) {
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
