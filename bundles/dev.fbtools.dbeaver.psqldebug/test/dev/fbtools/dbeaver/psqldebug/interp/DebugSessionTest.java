package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/** Sessão de debug (issue 13): Terminate no meio da execução e isolamento da transação. */
class DebugSessionTest {

    private static TestDatabase database;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute(
                "CREATE TABLE DS_LOG (N INTEGER)",
                "CREATE OR ALTER PROCEDURE DS_PONTO AS BEGIN END", """
                CREATE OR ALTER PROCEDURE DS_LONGO
                RETURNS (N INTEGER)
                AS
                  DECLARE FUNCTION UM RETURNS INTEGER AS BEGIN RETURN 1; END
                BEGIN
                  N = 0;
                  BEGIN
                    WHILE (N < 1000000) DO
                    BEGIN
                      INSERT INTO DS_LOG VALUES (:N);
                      N = N + UM();
                    END
                    WHEN ANY DO N = -1;
                  END
                END""", """
                CREATE OR ALTER PROCEDURE DS_CONTA
                RETURNS (ANTES INTEGER, DEPOIS INTEGER)
                AS
                BEGIN
                  SELECT COUNT(*) FROM DS_LOG INTO :ANTES;
                  EXECUTE PROCEDURE DS_PONTO;
                  SELECT COUNT(*) FROM DS_LOG INTO :DEPOIS;
                END""");
    }

    @BeforeEach
    void clearLog() throws SQLException {
        database.execute("DELETE FROM DS_LOG");
    }

    @Test
    void abortStopsALongLoopFromAnotherThreadWithRollback() throws Exception {
        RoutineDefinition routine = load("DS_LONGO");
        CountDownLatch running = new CountDownLatch(1);
        AtomicInteger statements = new AtomicInteger();
        StatementHook hook = (frame, statement) -> {
            if (statements.incrementAndGet() == 30) {
                running.countDown();
            }
        };
        try (DebugSession session =
                new DebugSession(database.connect(), DebugSession.Isolation.READ_COMMITTED, true, CallPolicy.SERVER,
                        hook)) {
            CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
                try {
                    session.run(routine, List.of());
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            assertTrue(running.await(30, TimeUnit.SECONDS));
            session.abort();
            // O WHEN ANY em volta do laço não trata a interrupção, e o "commit ao terminar" não vale.
            assertInstanceOf(SessionAbortedException.class, outcome.get(30, TimeUnit.SECONDS));
            assertEquals(0L, count("SELECT COUNT(*) FROM RDB$PACKAGES WHERE RDB$PACKAGE_NAME STARTING WITH 'DBG$'"));
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM DS_LOG"));
    }

    @Test
    void snapshotDoesNotSeeWhatOthersCommitDuringTheSession() throws Exception {
        assertEquals(List.of(0, 0), countsWith(DebugSession.Isolation.SNAPSHOT));
    }

    @Test
    void readCommittedSeesWhatOthersCommitDuringTheSession() throws Exception {
        assertEquals(List.of(0, 1), countsWith(DebugSession.Isolation.READ_COMMITTED));
    }

    /** {@code DS_CONTA}, com outra conexão gravando e commitando uma linha no meio da execução. */
    private static List<Object> countsWith(DebugSession.Isolation isolation) throws Exception {
        RoutineDefinition routine = load("DS_CONTA");
        CallPolicy insertElsewhere = (call, caller) -> {
            try {
                database.execute("INSERT INTO DS_LOG VALUES (99)");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return false;
        };
        try (DebugSession session =
                new DebugSession(database.connect(), isolation, false, insertElsewhere, StatementHook.NONE)) {
            return session.run(routine, List.of()).values();
        }
    }

    private static RoutineDefinition load(String procedure) throws SQLException {
        try (Connection connection = database.connect()) {
            return RoutineParser.parse(RoutineSourceLoader.loadProcedure(connection, procedure));
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
