package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.StatementLines;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Trigger isolada como o launch a roda (issue 19): a Fonte do banco e as colunas da tabela, as entradas na ordem da
 * Rotina ({@code NEW.*}, {@code OLD.*} e os eventos), o controle da sessão como gancho. Os breakpoints ficam nas linhas
 * que terminam com {@code -- tag}; Variables e Expressions são o {@link Frame} e o {@link DebugSession#watch} da pausa.
 */
class TriggerSessionTest {

    private static TestDatabase database;

    @BeforeAll
    static void createTriggers() throws Exception {
        database = TestDatabase.get();
        database.execute(
                "CREATE TABLE TS_CLIENTE (ID INTEGER NOT NULL, NOME VARCHAR(30), CODIGO VARCHAR(10), "
                        + "ORIGEM VARCHAR(10))",
                "CREATE TABLE TS_UNI (ID INTEGER, V VARCHAR(20))",
                "CREATE TABLE TS_LOG (TEXTO VARCHAR(50))", """
                CREATE TRIGGER TS_CLIENTE_BI FOR TS_CLIENTE ACTIVE BEFORE INSERT POSITION 0
                AS
                BEGIN
                  NEW.NOME = UPPER(TRIM(NEW.NOME));
                  NEW.CODIGO = 'C' || LPAD(NEW.ID, 4, '0'); -- codigo
                  IF (NEW.ORIGEM IS NULL) THEN NEW.ORIGEM = 'trigger';
                END""", """
                CREATE TRIGGER TS_UNI_BIUD FOR TS_UNI BEFORE INSERT OR UPDATE OR DELETE
                AS
                BEGIN
                  IF (INSERTING) THEN
                    NEW.V = 'novo'; -- insert
                  ELSE IF (UPDATING) THEN
                    NEW.V = OLD.V || '+alterado'; -- update
                  ELSE
                    INSERT INTO TS_LOG VALUES ('apagado ' || OLD.ID); -- delete
                END""");
    }

    @Test
    void beforeInsertStopsAtTheBreakpointWithNewInVariablesAndExpressionsAndEndsWithTheChangedNew()
            throws Exception {
        RoutineSourceLoader.TriggerSource source = load("TS_CLIENTE_BI");
        RoutineDefinition trigger = RoutineParser.parseTrigger(source.text(), source.columns());
        int codigo = line(source.text(), "codigo");
        List<Object> atPause = new ArrayList<>();

        ProcedureResult result = debug(trigger, Set.of(codigo), (session, top) -> {
            atPause.add(top.position().line());
            atPause.add(top.find("NEW.NOME").orElseThrow().value());
            atPause.add(top.find("OLD.NOME").orElseThrow().value());
            atPause.add(watch(session, top, "NEW.NOME || '/' || NEW.ID"));
            // Editado na pausa: o código sai do ID novo.
            assign(session, top, "NEW.ID", "42");
        }, inputs(trigger, Map.of("NEW.ID", 7, "NEW.NOME", "  ana "), Set.of("INSERTING")));

        assertEquals(Arrays.asList(codigo, "ANA", null, "ANA/7"), atPause);
        assertEquals(List.of("NEW.ID", "NEW.NOME", "NEW.CODIGO", "NEW.ORIGEM"), result.names());
        assertEquals(List.of(42, "ANA", "C0042", "trigger"), result.values());
    }

    @Test
    void universalTriggerFollowsTheBranchOfTheCheckedEvent() throws Exception {
        RoutineSourceLoader.TriggerSource source = load("TS_UNI_BIUD");
        RoutineDefinition trigger = RoutineParser.parseTrigger(source.text(), source.columns());
        Set<Integer> branches = Set.of(line(source.text(), "insert"), line(source.text(), "update"),
                line(source.text(), "delete"));
        Map<String, Object> row = Map.of("NEW.ID", 1, "NEW.V", "x", "OLD.ID", 1, "OLD.V", "antes");

        List<Integer> stops = new ArrayList<>();
        ProcedureResult updating = debug(trigger, branches, (session, top) -> stops.add(top.position().line()),
                inputs(trigger, row, Set.of("UPDATING")));
        assertEquals(List.of(line(source.text(), "update")), stops);
        assertEquals(List.of(1, "antes+alterado"), updating.values());

        stops.clear();
        ProcedureResult inserting = debug(trigger, branches, (session, top) -> stops.add(top.position().line()),
                inputs(trigger, row, Set.of("INSERTING")));
        assertEquals(List.of(line(source.text(), "insert")), stops);
        assertEquals(List.of(1, "novo"), inserting.values());

        // Sem evento marcado (o padrão do diálogo), nem INSERTING nem UPDATING: o ramo do ELSE.
        stops.clear();
        debug(trigger, branches, (session, top) -> stops.add(top.position().line()), inputs(trigger, row, Set.of()));
        assertEquals(List.of(line(source.text(), "delete")), stops);
    }

    @Test
    void breakpointLinesOfTheTriggerAreThoseOfItsBody() throws Exception {
        RoutineSourceLoader.TriggerSource source = load("TS_UNI_BIUD");
        // O editor do DBeaver mostra o texto da Fonte; os breakpoints vão para as linhas com statement dele.
        assertTrue(StatementLines.of(RoutineParser.parse(source.text()))
                .containsAll(Set.of(line(source.text(), "insert"), line(source.text(), "update"))));
    }

    /**
     * Valores na ordem das entradas da trigger, como o diálogo os monta: {@code NULL} na coluna não informada e
     * {@code TRUE} só nos eventos marcados.
     */
    private static List<Object> inputs(RoutineDefinition trigger, Map<String, Object> values, Set<String> events) {
        List<Object> inputs = new ArrayList<>();
        for (ParameterDeclaration input : trigger.inputs()) {
            inputs.add(input.type().equals("BOOLEAN") ? events.contains(input.name()) : values.get(input.name()));
        }
        return inputs;
    }

    private static Object watch(DebugSession session, Frame frame, String text) {
        try {
            return session.watch(frame, text).value();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assign(DebugSession session, Frame frame, String name, String text) {
        try {
            session.assign(frame, frame.find(name).orElseThrow(), text);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RoutineSourceLoader.TriggerSource load(String name) throws SQLException {
        try (Connection connection = database.connect()) {
            return RoutineSourceLoader.loadTrigger(connection, name);
        }
    }

    /**
     * Roda a trigger na Sessão de debug. A cada pausa, na thread da execução (onde a view Variables e os watches
     * rodam suas tarefas), chama {@code atPause} com o frame de cima e manda resume.
     */
    private static ProcedureResult debug(RoutineDefinition trigger, Set<Integer> breakpoints,
            BiConsumer<DebugSession, Frame> atPause, List<Object> inputs) throws SQLException {
        AtomicReference<DebugSession> session = new AtomicReference<>();
        AtomicReference<ExecutionControl> control = new AtomicReference<>();
        control.set(new ExecutionControl((source, line) -> breakpoints.contains(line) ? line : null, pause -> {
            atPause.accept(session.get(), pause.frames().get(0));
            control.get().resume();
        }, () -> session.get().frames()));
        try (DebugSession opened = new DebugSession(database.connect(), DebugSession.Isolation.READ_COMMITTED, false,
                control.get(), control.get())) {
            session.set(opened);
            return opened.run(trigger, inputs);
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
}
