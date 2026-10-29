package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.PreCheck;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.StatementLines;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Procedure selecionável, function, rotina de package e {@code EXECUTE BLOCK} como Rotina raiz da Sessão de debug,
 * como o launch os roda (issue 18): o controle da sessão como gancho e como política das chamadas, e as linhas do
 * {@code SUSPEND} entregues à medida que são emitidas. Os breakpoints ficam nas linhas que terminam com
 * {@code -- tag}.
 */
class RootRoutineSessionTest {

    private static TestDatabase database;

    /** Uma pausa: a Rotina de cima, a linha e a altura da pilha. */
    private record Stop(String routine, int line, int depth) {
    }

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute("""
                CREATE OR ALTER PROCEDURE RS_QUADRADOS (N INTEGER)
                RETURNS (I INTEGER, Q INTEGER)
                AS
                BEGIN
                  I = 0;
                  WHILE (I < N) DO
                  BEGIN
                    I = I + 1;
                    Q = I * I; -- quadrado
                    SUSPEND;
                  END
                END""", """
                CREATE OR ALTER FUNCTION RS_DOBRO (X INTEGER) RETURNS INTEGER
                AS
                  DECLARE R INTEGER;
                BEGIN
                  R = X * 2; -- dobro
                  RETURN R;
                END""",
                "CREATE OR ALTER PACKAGE RS_PKG AS BEGIN PROCEDURE TOTAL (N INTEGER) RETURNS (S INTEGER); END", """
                RECREATE PACKAGE BODY RS_PKG
                AS
                BEGIN
                  FUNCTION TRIPLO (X INTEGER) RETURNS INTEGER
                  AS
                  BEGIN
                    RETURN X * 3; -- triplo
                  END

                  PROCEDURE TOTAL (N INTEGER) RETURNS (S INTEGER)
                  AS
                    DECLARE I INTEGER = 0;
                    DECLARE T INTEGER;
                  BEGIN
                    S = 0;
                    WHILE (I < N) DO
                    BEGIN
                      I = I + 1;
                      T = TRIPLO(I); -- chama
                      S = S + T;
                    END
                  END
                END""");
    }

    @Test
    void selectableDeliversEachRowWhenItIsEmittedSoThePauseSeesTheRowsBefore() throws Exception {
        String text = load(connection -> RoutineSourceLoader.loadProcedure(connection, "RS_QUADRADOS"));
        RoutineDefinition procedure = RoutineParser.parse(text);
        int quadrado = line(text, "quadrado");
        List<List<Object>> rows = new ArrayList<>();
        List<Integer> rowsAtPause = new ArrayList<>();
        List<Stop> stops = new ArrayList<>();

        debug((source, line) -> line == quadrado, stops, () -> rowsAtPause.add(rows.size()), session -> {
            session.select(procedure, List.of(3), rows::add);
            return null;
        });

        assertEquals(List.of(new Stop("RS_QUADRADOS", quadrado, 1), new Stop("RS_QUADRADOS", quadrado, 1),
                new Stop("RS_QUADRADOS", quadrado, 1)), stops);
        assertEquals(List.of(0, 1, 2), rowsAtPause);
        assertEquals(List.of(List.of(1, 1), List.of(2, 4), List.of(3, 9)), rows);
        assertEquals(nativeRows("SELECT * FROM RS_QUADRADOS(3)"), rows);
    }

    @Test
    void functionStopsAtItsBreakpointAndReturnsTheValue() throws Exception {
        String text = load(connection -> RoutineSourceLoader.loadFunction(connection, "RS_DOBRO"));
        RoutineDefinition function = RoutineParser.parse(text);
        int dobro = line(text, "dobro");
        List<Stop> stops = new ArrayList<>();

        ProcedureResult result = debug((source, line) -> "RS_DOBRO".equals(source.name()) && line == dobro, stops,
                () -> {
                }, session -> session.run(function, List.of(21)));

        assertEquals(List.of(new Stop("RS_DOBRO", dobro, 1)), stops);
        assertEquals(List.of("RS_DOBRO"), result.names());
        assertEquals(List.of(42), result.values());
    }

    @Test
    void packageRoutineStopsAtTheLinesOfTheBodyAlsoInTheRoutineItCalls() throws Exception {
        RoutineSourceLoader.PackageSource pkg = load(connection -> RoutineSourceLoader.loadPackage(connection,
                "RS_PKG"));
        RoutineDefinition total = RoutineParser.parsePackageRoutine(pkg.header(), pkg.body(),
                RoutineDefinition.Kind.PROCEDURE, "TOTAL");
        int chama = line(pkg.body(), "chama");
        int triplo = line(pkg.body(), "triplo");
        List<Stop> stops = new ArrayList<>();

        // Os breakpoints são do texto do corpo, o mesmo de todas as rotinas da package.
        ProcedureResult result = debug((source, line) -> "RS_PKG".equals(source.packageName())
                && Set.of(chama, triplo).contains(line), stops, () -> {
                }, session -> session.run(total, List.of(2)));

        assertEquals(List.of(new Stop("RS_PKG.TOTAL", chama, 1), new Stop("RS_PKG.TRIPLO", triplo, 2),
                new Stop("RS_PKG.TOTAL", chama, 1), new Stop("RS_PKG.TRIPLO", triplo, 2)), stops);
        assertEquals(List.of(9), result.values());
        assertEquals(nativeRows("EXECUTE PROCEDURE RS_PKG.TOTAL(2)"), List.of(result.values()));
        // No console da package, os breakpoints vão para as linhas com statement de todas as rotinas do corpo.
        NavigableSet<Integer> lines = new TreeSet<>();
        RoutineParser.parsePackageBody(pkg.body())
                .forEach(routine -> lines.addAll(StatementLines.of(routine.routine())));
        assertTrue(lines.containsAll(Set.of(chama, triplo)), lines.toString());
    }

    @Test
    void executeBlockOfTheSqlEditorHasThePositionsOfTheDocument() throws Exception {
        String document = """
                SELECT 1 FROM RDB$DATABASE;

                  EXECUTE BLOCK (N INTEGER = ?) RETURNS (R INTEGER)
                  AS
                  BEGIN
                    R = N + 1; -- soma
                    SUSPEND;
                  END""";
        String block = document.substring(document.indexOf("EXECUTE"));
        RoutineDefinition routine = RoutineParser.parse(RoutineParser.placed(block, 3, 3));
        int soma = line(document, "soma");
        List<Stop> stops = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();

        debug((source, line) -> source == routine && line == soma, stops, () -> {
        }, session -> {
            session.select(routine, List.of(41), rows::add);
            return null;
        });

        assertEquals(List.of(new Stop("EXECUTE BLOCK", 6, 1)), stops);
        assertEquals(List.of(List.of(42)), rows);
        // O Pré-check também fala nas posições do documento.
        String refused = block.replace("R = N + 1;", "R = N + 1;\n    UPDATE RDB$DATABASE SET RDB$DESCRIPTION = NULL"
                + " WHERE CURRENT OF C;");
        List<PreCheck.Finding> findings = PreCheck.check(RoutineParser.placed(refused, 3, 3));
        assertEquals(new SourcePosition(7, 52), findings.get(0).position());
    }

    @FunctionalInterface
    private interface Read<T> {
        T run(Connection connection) throws SQLException;
    }

    private static <T> T load(Read<T> read) throws SQLException {
        try (Connection connection = database.connect()) {
            return read.run(connection);
        }
    }

    @FunctionalInterface
    private interface Run<T> {
        T run(DebugSession session) throws SQLException;
    }

    /**
     * Roda na Sessão de debug como o launch: o controle da sessão como gancho e como política das chamadas. A cada
     * pausa, registra a Rotina de cima, chama {@code atPause} e manda resume.
     *
     * @param breakpoints se há breakpoint na linha do texto da Rotina ({@link Frame#sourceRoutine()})
     */
    private static <T> T debug(BiPredicate<RoutineDefinition, Integer> breakpoints, List<Stop> stops,
            Runnable atPause, Run<T> run) throws SQLException {
        AtomicReference<DebugSession> session = new AtomicReference<>();
        AtomicReference<ExecutionControl> control = new AtomicReference<>();
        control.set(new ExecutionControl((source, line) -> breakpoints.test(source, line) ? line : null, pause -> {
            Frame top = pause.frames().get(0);
            stops.add(new Stop(top.routine().displayName(), top.position().line(), pause.frames().size()));
            atPause.run();
            control.get().resume();
        }, () -> session.get().frames()));
        try (DebugSession opened = new DebugSession(database.connect(), DebugSession.Isolation.READ_COMMITTED, false,
                control.get(), control.get())) {
            session.set(opened);
            return run.run(opened);
        }
    }

    /** Linha do texto que termina com {@code -- tag}. */
    private static int line(String text, String tag) {
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripTrailing().endsWith("-- " + tag)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("sem a tag " + tag);
    }

    private static List<List<Object>> nativeRows(String sql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            int columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(resultSet.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
