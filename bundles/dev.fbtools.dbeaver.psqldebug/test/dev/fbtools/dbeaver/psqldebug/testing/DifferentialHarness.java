package dev.fbtools.dbeaver.psqldebug.testing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.fbtools.dbeaver.psqldebug.interp.CallPolicy;
import dev.fbtools.dbeaver.psqldebug.interp.DatabaseRoutineCatalog;
import dev.fbtools.dbeaver.psqldebug.interp.Interpreter;
import dev.fbtools.dbeaver.psqldebug.interp.ProcedureResult;
import dev.fbtools.dbeaver.psqldebug.interp.TemporaryPackages;
import dev.fbtools.dbeaver.psqldebug.psql.Identifiers;
import dev.fbtools.dbeaver.psqldebug.psql.PreCheck;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;

/**
 * Teste diferencial (ADR 0005): executa a mesma Rotina no servidor e no Interpretador, cada uma na sua transação
 * com rollback, e compara parâmetros de saída, exceção e o estado das tabelas tocadas antes do rollback. O servidor
 * é o gabarito.
 */
public final class DifferentialHarness {

    private final TestDatabase database;
    private final List<String> tables;
    private final CallPolicy policy;

    public DifferentialHarness(TestDatabase database) {
        this(database, List.of(), CallPolicy.SERVER);
    }

    private DifferentialHarness(TestDatabase database, List<String> tables, CallPolicy policy) {
        this.database = database;
        this.tables = List.copyOf(tables);
        this.policy = policy;
    }

    /** Harness que compara também o conteúdo destas tabelas, lido antes do rollback de cada execução. */
    public DifferentialHarness tables(String... names) {
        return new DifferentialHarness(database, Arrays.asList(names), policy);
    }

    /**
     * Harness em que o Interpretador escolhe com {@code policy} entre interpretar e executar no servidor cada
     * chamada elegível. O padrão é executar todas no servidor.
     */
    public DifferentialHarness calls(CallPolicy policy) {
        return new DifferentialHarness(database, tables, policy);
    }

    /**
     * Executa a procedure executável dos dois jeitos ({@code EXECUTE PROCEDURE} no servidor) e falha mostrando cada
     * divergência: saída, exceção (código, SQLSTATE e mensagem) ou linha de tabela.
     */
    public void assertSameOutputs(String procedure, Object... inputs) throws SQLException {
        assertSameOutputs(Target.procedure(null, procedure, inputs.length), Arrays.asList(inputs));
    }

    /**
     * Executa a procedure selecionável dos dois jeitos, lendo todas as linhas ({@code SELECT * FROM sp(...)} no
     * servidor), e falha mostrando cada divergência: linha emitida (ordem e valores), exceção ou linha de tabela.
     * Se a Rotina falha, as linhas emitidas antes do erro também são comparadas.
     */
    public void assertSameRows(String procedure, Object... inputs) throws SQLException {
        assertSameRows(Target.procedure(null, procedure, inputs.length), Arrays.asList(inputs));
    }

    /** Como {@link #assertSameOutputs}, para a function ({@code SELECT f(...) FROM RDB$DATABASE} no servidor). */
    public void assertSameResult(String function, Object... inputs) throws SQLException {
        assertSameOutputs(Target.function(null, function, inputs.length), Arrays.asList(inputs));
    }

    /** Como {@link #assertSameOutputs}, para a procedure de package ({@code EXECUTE PROCEDURE pkg.sp}). */
    public void assertSamePackageOutputs(String pkg, String procedure, Object... inputs) throws SQLException {
        assertSameOutputs(Target.procedure(pkg, procedure, inputs.length), Arrays.asList(inputs));
    }

    /** Como {@link #assertSameRows}, para a procedure selecionável de package. */
    public void assertSamePackageRows(String pkg, String procedure, Object... inputs) throws SQLException {
        assertSameRows(Target.procedure(pkg, procedure, inputs.length), Arrays.asList(inputs));
    }

    /** Como {@link #assertSameResult}, para a function de package ({@code SELECT pkg.f(...) FROM RDB$DATABASE}). */
    public void assertSamePackageResult(String pkg, String function, Object... inputs) throws SQLException {
        assertSameOutputs(Target.function(pkg, function, inputs.length), Arrays.asList(inputs));
    }

    /**
     * {@code EXECUTE BLOCK} executado direto no servidor, com {@code inputs} nos {@code ?}, comparado como
     * {@link #assertSameRows}: o motor só devolve o que o {@code SUSPEND} emite, mesmo num bloco sem ele. Se o bloco
     * falha, não há como saber quais linhas ele emitiu antes do erro (ver {@link #runNative(Target, List, boolean)}):
     * só as que o Jaybird entregou são comparadas, com o começo das linhas do Interpretador.
     */
    public void assertSameExecuteBlock(String block, Object... inputs) throws SQLException {
        assertSameRows(Target.executeBlock(block), Arrays.asList(inputs));
    }

    /**
     * Trigger isolada: {@code dml} executado no servidor dispara a trigger, e o Interpretador roda a trigger com
     * {@code inputs} ({@code NEW.campo}, {@code OLD.campo}, {@code INSERTING}, {@code UPDATING}, {@code DELETING};
     * os que faltarem valem {@code NULL}, e {@code FALSE} nos eventos). Compara a exceção e as tabelas (que não devem
     * incluir a da trigger, que só o DML nativo grava) e, com {@code writtenRow}, os {@code NEW.*} finais com a linha
     * gravada, lida por esse {@code SELECT} depois do DML.
     *
     * @param writtenRow {@code SELECT * FROM tabela WHERE ...} da linha gravada; {@code null} numa trigger
     *            {@code AFTER} ou num {@code DELETE}
     */
    public void assertSameTrigger(String trigger, String dml, String writtenRow, Map<String, Object> inputs)
            throws SQLException {
        String call = "trigger " + trigger + " [" + dml + "]";
        Outcome expected;
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                List<String> names = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();
                SQLException error = null;
                try (Statement statement = connection.createStatement()) {
                    statement.execute(dml);
                    if (writtenRow != null) {
                        readRows(statement.executeQuery(writtenRow), true, names, rows);
                    }
                } catch (SQLException e) {
                    error = e;
                }
                expected = new Outcome(names, rows, error, snapshot(connection));
            } finally {
                connection.rollback();
            }
        }
        Outcome actual;
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            TemporaryPackages packages = new TemporaryPackages(connection);
            try {
                RoutineSourceLoader.TriggerSource source = RoutineSourceLoader.loadTrigger(connection, trigger);
                RoutineDefinition definition = RoutineParser.parseTrigger(source.text(), source.columns());
                List<PreCheck.Finding> findings = PreCheck.scan(definition);
                if (!findings.isEmpty()) {
                    throw new AssertionError(trigger + ": Pré-check recusou a Rotina: " + findings);
                }
                List<String> declared = definition.inputs().stream().map(ParameterDeclaration::name).toList();
                if (!declared.containsAll(inputs.keySet())) {
                    throw new AssertionError(trigger + ": entradas desconhecidas em " + inputs.keySet()
                            + "; a trigger tem " + declared);
                }
                List<Object> values = new ArrayList<>();
                for (ParameterDeclaration input : definition.inputs()) {
                    values.add(inputs.containsKey(input.name()) ? inputs.get(input.name())
                            : input.type().equals("BOOLEAN") ? Boolean.FALSE : null);
                }
                List<String> names = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();
                SQLException error = null;
                try {
                    ProcedureResult result = new Interpreter(connection, new DatabaseRoutineCatalog(connection),
                            policy, packages).execute(definition, values);
                    result.names().forEach(name -> names.add(name.substring("NEW.".length())));
                    rows.add(result.values());
                } catch (SQLException e) {
                    error = e;
                }
                actual = new Outcome(names, rows, error, snapshot(connection));
            } finally {
                connection.rollback();
                packages.dropAll();
            }
        }
        OutputComparison.assertSameError(call, expected.error(), actual.error());
        if (expected.error() == null && writtenRow != null) {
            if (expected.rows().size() != 1) {
                throw new AssertionError(writtenRow + " devolveu " + expected.rows().size() + " linha(s)");
            }
            OutputComparison.assertSame(call, expected.names(), expected.rows().get(0), actual.names(),
                    actual.rows().get(0));
        }
        OutputComparison.assertSameTables(call, expected.tables(), actual.tables());
    }

    private void assertSameOutputs(Target target, List<Object> inputs) throws SQLException {
        Outcome expected = runNative(target, inputs, false);
        Outcome actual = runInterpreted(target, inputs, false);
        String call = target.describe(inputs);
        OutputComparison.assertSameError(call, expected.error(), actual.error());
        if (expected.error() == null) {
            OutputComparison.assertSame(call, expected.names(), expected.rows().get(0), actual.names(),
                    actual.rows().get(0));
        }
        OutputComparison.assertSameTables(call, expected.tables(), actual.tables());
    }

    private void assertSameRows(Target target, List<Object> inputs) throws SQLException {
        Outcome expected = runNative(target, inputs, true);
        Outcome actual = runInterpreted(target, inputs, true);
        String call = target.describe(inputs);
        OutputComparison.assertSameError(call, expected.error(), actual.error());
        List<List<Object>> actualRows = actual.rows();
        if (expected.error() != null && target.firstRows(1) == null) {
            actualRows = actualRows.subList(0, Math.min(actualRows.size(), expected.rows().size()));
        }
        OutputComparison.assertSameRows(call, expected.names(), expected.rows(), actual.names(), actualRows);
        OutputComparison.assertSameTables(call, expected.tables(), actual.tables());
    }

    /** @param select lendo todas as linhas; senão, como executável, que devolve uma linha só */
    private Outcome runNative(Target target, List<Object> inputs, boolean select) throws SQLException {
        if (!select) {
            Outcome outcome = runNative(target.executeSql(), inputs, false);
            if (outcome.error() == null && outcome.rows().size() != 1) {
                throw new AssertionError(target.executeSql() + " devolveu " + outcome.rows().size() + " linha(s)");
            }
            return outcome;
        }
        Outcome outcome = runNative(target.selectSql(), inputs, true);
        if (outcome.error() == null || target.firstRows(1) == null) {
            return outcome;
        }
        // O servidor busca linhas à frente e manda o erro junto com as últimas linhas boas, que o Jaybird descarta
        // ao lançar o erro. Quais linhas a Rotina emitiu antes do erro sai de SELECT FIRST n, cada um na sua
        // transação: o FIRST para a Rotina depois da linha n, sem buscar a seguinte.
        List<List<Object>> rows = outcome.rows();
        for (int n = rows.size() + 1;; n++) {
            Outcome first = runNative(target.firstRows(n), inputs, true);
            if (first.error() != null || first.rows().size() < n) {
                break;
            }
            rows = first.rows();
        }
        return new Outcome(outcome.names(), rows, outcome.error(), outcome.tables());
    }

    /** Executa {@code sql} numa transação própria, lê todas as linhas e as tabelas, e faz rollback. */
    private Outcome runNative(String sql, List<Object> inputs, boolean select) throws SQLException {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                List<String> names = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < inputs.size(); i++) {
                        statement.setObject(i + 1, inputs.get(i));
                    }
                    statement.execute();
                    readRows(statement.getResultSet(), select, names, rows);
                } catch (SQLException e) {
                    return new Outcome(names, rows, e, snapshot(connection));
                }
                return new Outcome(names, rows, null, snapshot(connection));
            } finally {
                connection.rollback();
            }
        }
    }

    /** Os Packages temporários de debug que o Interpretador criar são removidos depois do rollback. */
    private Outcome runInterpreted(Target target, List<Object> inputs, boolean select) throws SQLException {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            TemporaryPackages packages = new TemporaryPackages(connection);
            try {
                RoutineDefinition definition = target.load(connection);
                List<PreCheck.Finding> findings = PreCheck.scan(definition);
                if (!findings.isEmpty()) {
                    throw new AssertionError(target.name() + ": Pré-check recusou a Rotina: " + findings);
                }
                List<String> names = definition.outputs().stream().map(ParameterDeclaration::name).toList();
                List<List<Object>> rows = new ArrayList<>();
                try {
                    Interpreter interpreter =
                            new Interpreter(connection, new DatabaseRoutineCatalog(connection), policy, packages);
                    if (select) {
                        interpreter.select(definition, inputs, rows::add);
                    } else {
                        ProcedureResult result = interpreter.execute(definition, inputs);
                        names = result.names();
                        rows.add(result.values());
                    }
                } catch (SQLException e) {
                    return new Outcome(names, rows, e, snapshot(connection));
                }
                return new Outcome(names, rows, null, snapshot(connection));
            } finally {
                connection.rollback();
                packages.dropAll();
            }
        }
    }

    /** Fonte do banco da Rotina, já parseada. */
    @FunctionalInterface
    private interface Loader {
        RoutineDefinition load(Connection connection) throws SQLException;
    }

    /**
     * Rotina sob teste: como chamá-la no servidor e como carregá-la para o Interpretador.
     *
     * @param selectSql {@code SELECT * FROM ...}, ou {@code null} se não há como ler linhas
     */
    private record Target(String name, String executeSql, String selectSql, Loader loader) {

        static Target procedure(String pkg, String procedure, int inputs) {
            String call = qualified(pkg, procedure) + arguments(inputs, true);
            Loader loader = pkg == null
                    ? connection -> RoutineParser.parse(RoutineSourceLoader.loadProcedure(connection, procedure))
                    : connection -> packageRoutine(connection, pkg, RoutineDefinition.Kind.PROCEDURE, procedure);
            return new Target(call, "EXECUTE PROCEDURE " + call, "SELECT * FROM " + call, loader);
        }

        static Target function(String pkg, String function, int inputs) {
            String call = qualified(pkg, function) + arguments(inputs, false);
            Loader loader = pkg == null
                    ? connection -> RoutineParser.parse(RoutineSourceLoader.loadFunction(connection, function))
                    : connection -> packageRoutine(connection, pkg, RoutineDefinition.Kind.FUNCTION, function);
            return new Target(call, "SELECT " + call + " FROM RDB$DATABASE", null, loader);
        }

        static Target executeBlock(String block) {
            return new Target("EXECUTE BLOCK", block, block, connection -> RoutineParser.parse(block));
        }

        RoutineDefinition load(Connection connection) throws SQLException {
            return loader.load(connection);
        }

        /** {@code SELECT FIRST n * FROM ...}; {@code null} se a Rotina não é chamada por {@code SELECT * FROM}. */
        String firstRows(int n) {
            return selectSql == null || !selectSql.startsWith("SELECT * FROM ") ? null
                    : "SELECT FIRST " + n + " * FROM " + selectSql.substring("SELECT * FROM ".length());
        }

        String describe(List<Object> inputs) {
            return name + inputs.stream().map(OutputComparison::describe).collect(Collectors.joining(", ", " [", "]"));
        }

        private static String qualified(String pkg, String routine) {
            return (pkg == null ? "" : Identifiers.quote(pkg) + ".") + Identifiers.quote(routine);
        }

        /** {@code (?, ?)}; numa procedure sem entradas, nada (numa function, {@code ()}). */
        private static String arguments(int count, boolean procedure) {
            return count == 0 && procedure ? "" : "(" + String.join(", ", Collections.nCopies(count, "?")) + ")";
        }

        private static RoutineDefinition packageRoutine(Connection connection, String pkg,
                RoutineDefinition.Kind kind, String routine) throws SQLException {
            RoutineSourceLoader.PackageSource source = RoutineSourceLoader.loadPackage(connection, pkg);
            return RoutineParser.parsePackageRoutine(source.header(), source.body(), kind, routine);
        }
    }


    /** Linhas de cada tabela, descritas e ordenadas, para não depender da ordem física. */
    private Map<String, List<String>> snapshot(Connection connection) throws SQLException {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : tables) {
            List<String> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT * FROM " + Identifiers.quote(table))) {
                int columns = resultSet.getMetaData().getColumnCount();
                while (resultSet.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        values.add(OutputComparison.describe(resultSet.getObject(i)));
                    }
                    rows.add(String.join(", ", values));
                }
            }
            Collections.sort(rows);
            snapshot.put(table, rows);
        }
        return snapshot;
    }

    /**
     * Lê as colunas e todas as linhas. Se a leitura falhar no meio, o que já foi lido fica em {@code rows}. Sem
     * resultado (Rotina sem saídas), uma executável dá uma linha vazia, e uma selecionável, nenhuma.
     */
    private static void readRows(ResultSet resultSet, boolean select, List<String> names, List<List<Object>> rows)
            throws SQLException {
        if (resultSet == null) {
            if (!select) {
                rows.add(List.of());
            }
            return;
        }
        try (resultSet) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                names.add(metaData.getColumnLabel(i));
            }
            while (resultSet.next()) {
                List<Object> values = new ArrayList<>();
                for (int i = 1; i <= names.size(); i++) {
                    values.add(resultSet.getObject(i));
                }
                rows.add(values);
            }
        }
    }

    /**
     * Resultado de uma execução: as linhas (uma só, as saídas, no {@code EXECUTE PROCEDURE}) e a exceção que a
     * terminou, se houve; e as tabelas antes do rollback.
     */
    private record Outcome(List<String> names, List<List<Object>> rows, SQLException error,
            Map<String, List<String>> tables) {
    }
}
