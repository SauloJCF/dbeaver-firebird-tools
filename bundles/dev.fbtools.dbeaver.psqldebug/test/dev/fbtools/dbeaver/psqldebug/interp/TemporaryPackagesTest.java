package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Ciclo de vida do Package temporário de debug (issue 11): criado quando uma expressão chama uma sub-rotina,
 * removido em qualquer forma de encerramento da Sessão de debug, e varrido na sessão seguinte se a anterior morreu
 * sem removê-lo. O DDL não commita nada da Rotina.
 */
class TemporaryPackagesTest {

    private static TestDatabase database;
    private static RoutineDefinition routine;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute(
                "CREATE TABLE TP_LOG (TEXTO VARCHAR(100))",
                "CREATE OR ALTER PROCEDURE TP_PONTO AS BEGIN END", """
                CREATE OR ALTER PROCEDURE TP_SUB (K INTEGER)
                RETURNS (R INTEGER)
                AS
                  DECLARE FUNCTION DOBRO (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X * 2; END
                BEGIN
                  INSERT INTO TP_LOG VALUES ('antes');
                  R = DOBRO(K) + 0; -- dentro de expressão: roda no servidor, pelo package
                  EXECUTE PROCEDURE TP_PONTO; -- chamada elegível: a política vê o package criado
                  R = R / K;
                  INSERT INTO TP_LOG VALUES ('depois');
                END""");
        try (Connection connection = database.connect()) {
            routine = RoutineParser.parse(RoutineSourceLoader.loadProcedure(connection, "TP_SUB"));
        }
    }

    @BeforeEach
    void startClean() throws SQLException {
        List<String> statements = new ArrayList<>();
        for (String name : packages()) {
            statements.add("DROP PACKAGE \"" + name + "\"");
        }
        statements.add("DELETE FROM TP_LOG");
        database.execute(statements.toArray(String[]::new));
    }

    @Test
    void packageExistsDuringTheSessionAndIsRemovedAtTheEnd() throws SQLException {
        List<List<String>> during = new ArrayList<>();
        try (DebugSession session = new DebugSession(database.connect(), false, (call, caller) -> {
            during.add(packagesQuietly());
            return false;
        })) {
            ProcedureResult result = session.run(routine, List.of(3));
            assertEquals(List.of(2), result.values());
            assertEquals(List.of(), packages());
        }
        assertEquals(1, during.size());
        assertEquals(1, during.get(0).size());
        assertTrue(during.get(0).get(0).matches("DBG\\$\\d+_1"), during.get(0).get(0));
        assertEquals(List.of(), log(), "o DDL do package não pode commitar a transação da sessão");
    }

    @Test
    void commitOnFinishCommitsOnlyTheRoutine() throws SQLException {
        try (DebugSession session = new DebugSession(database.connect(), true)) {
            session.run(routine, List.of(3));
        }
        assertEquals(List.of(), packages());
        assertEquals(List.of("antes", "depois"), log());
    }

    @Test
    void packageIsRemovedWhenTheRoutineFails() throws SQLException {
        try (DebugSession session = new DebugSession(database.connect(), true)) {
            assertThrows(SQLException.class, () -> session.run(routine, List.of(0)));
            assertEquals(List.of(), packages());
        }
        assertEquals(List.of(), log());
    }

    @Test
    void packageIsRemovedWhenTheSessionIsAborted() throws SQLException {
        try (DebugSession session = new DebugSession(database.connect(), true, (call, caller) -> {
            throw new IllegalStateException("abortado");
        })) {
            assertThrows(IllegalStateException.class, () -> session.run(routine, List.of(3)));
        }
        assertEquals(List.of(), packages());
        assertEquals(List.of(), log());
    }

    @Test
    void nextSessionSweepsThePackageOfASessionThatDied() throws SQLException {
        Connection dead = database.connect();
        dead.setAutoCommit(false);
        new Interpreter(dead, new DatabaseRoutineCatalog(dead), CallPolicy.SERVER, new TemporaryPackages(dead))
                .execute(routine, List.of(3));
        dead.close(); // sem remover o package, como se o DBeaver tivesse morrido
        assertEquals(1, packages().size());

        try (DebugSession session = new DebugSession(database.connect(), false)) {
            assertEquals(List.of(), packages());
        }
    }

    @Test
    void sweepKeepsThePackagesOfSessionsStillRunning() throws SQLException {
        try (Connection running = database.connect()) {
            running.setAutoCommit(false);
            TemporaryPackages packages = new TemporaryPackages(running);
            new Interpreter(running, new DatabaseRoutineCatalog(running), CallPolicy.SERVER, packages)
                    .execute(routine, List.of(3));
            List<String> created = packages();
            assertEquals(1, created.size());

            try (DebugSession session = new DebugSession(database.connect(), false)) {
                assertEquals(created, packages());
            }
            running.rollback();
            packages.dropAll();
            assertEquals(List.of(), packages());
        }
    }

    private static List<String> packagesQuietly() {
        try {
            return packages();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Packages {@code DBG$} no banco, vistos de outra conexão. */
    private static List<String> packages() throws SQLException {
        return strings("SELECT TRIM(RDB$PACKAGE_NAME) FROM RDB$PACKAGES WHERE RDB$PACKAGE_NAME STARTING WITH 'DBG$' "
                + "ORDER BY 1");
    }

    private static List<String> log() throws SQLException {
        return strings("SELECT TEXTO FROM TP_LOG ORDER BY TEXTO");
    }

    private static List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }
}
