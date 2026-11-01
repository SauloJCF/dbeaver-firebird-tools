package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Views lidas de {@code RDB$*} (ADR 0007) e ordenadas por {@code RDB$DEPENDENCIES}: a view que lê outra vem depois
 * dela. Uma view que lê procedure selecionável não pode vir antes do Cabeçalho vazio da procedure, então sai à parte.
 */
final class ViewCatalog {

    private static final String VIEW_NAMES = """
            SELECT RDB$RELATION_NAME FROM RDB$RELATIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$VIEW_BLR IS NOT NULL ORDER BY RDB$RELATION_NAME""";

    private static final String VIEWS = """
            SELECT RDB$RELATION_NAME, RDB$VIEW_SOURCE FROM RDB$RELATIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$VIEW_BLR IS NOT NULL ORDER BY RDB$RELATION_NAME""";

    private static final String COLUMNS = """
            SELECT rf.RDB$RELATION_NAME, rf.RDB$FIELD_NAME FROM RDB$RELATION_FIELDS rf
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = rf.RDB$RELATION_NAME
             WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0 AND r.RDB$VIEW_BLR IS NOT NULL
             ORDER BY rf.RDB$RELATION_NAME, rf.RDB$FIELD_POSITION""";

    // Dependente tipo 1 é view; o que ela lê é tabela (0), view (1) ou procedure (5).
    private static final String DEPENDENCIES = """
            SELECT DISTINCT d.RDB$DEPENDENT_NAME, d.RDB$DEPENDED_ON_NAME, d.RDB$DEPENDED_ON_TYPE
              FROM RDB$DEPENDENCIES d
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = d.RDB$DEPENDENT_NAME
             WHERE d.RDB$DEPENDENT_TYPE = 1 AND d.RDB$DEPENDED_ON_TYPE IN (0, 1, 5)
               AND COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0 AND r.RDB$VIEW_BLR IS NOT NULL""";

    private final Connection connection;

    ViewCatalog(Connection connection) {
        this.connection = connection;
    }

    /**
     * Uma view. {@code relations} são as tabelas e views que ela lê, e {@code procedures} as procedures; {@code source}
     * é a consulta como o autor escreveu ({@code RDB$VIEW_SOURCE}).
     */
    record View(String name, List<String> columns, String source, Set<String> relations, Set<String> procedures) {

        String toSql(ExtractionOptions options) {
            return (options.createOrAlter() ? "CREATE OR ALTER VIEW " : "CREATE VIEW ") + options.quote(name) + " ("
                    + columns.stream().map(options::quote).collect(Collectors.joining(", ")) + ") AS\n" + source
                    + ";";
        }
    }

    /** As views da seleção em ordem topológica; {@code afterRoutines} guarda as que dependem de procedure. */
    record Views(List<View> beforeRoutines, List<View> afterRoutines) {

        int size() {
            return beforeRoutines.size() + afterRoutines.size();
        }

        List<View> all() {
            List<View> all = new ArrayList<>(beforeRoutines);
            all.addAll(afterRoutines);
            return all;
        }
    }

    List<String> listViewNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(VIEW_NAMES)) {
            while (rs.next()) {
                names.add(rs.getString(1).strip());
            }
        }
        return names;
    }

    Views readViews(Selection selection, ExtractionProgress progress) throws SQLException {
        Map<String, List<String>> columns = readColumns();
        Map<String, Set<String>> relations = new TreeMap<>();
        Map<String, Set<String>> procedures = new TreeMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(DEPENDENCIES)) {
            while (rs.next()) {
                String view = rs.getString(1).strip();
                String target = rs.getString(2).strip();
                (rs.getInt(3) == 5 ? procedures : relations).computeIfAbsent(view, k -> new TreeSet<>()).add(target);
            }
        }
        Map<String, View> views = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(VIEWS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                if (selection.includesView(name)) {
                    views.put(name, new View(name, columns.getOrDefault(name, List.of()),
                            Rdb.source(rs.getString(2)), relations.getOrDefault(name, Set.of()),
                            procedures.getOrDefault(name, Set.of())));
                }
            }
        }
        return order(views);
    }

    private Map<String, List<String>> readColumns() throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(COLUMNS)) {
            while (rs.next()) {
                columns.computeIfAbsent(rs.getString(1).strip(), k -> new ArrayList<>()).add(rs.getString(2).strip());
            }
        }
        return columns;
    }

    /**
     * Depth-first sobre os nomes em ordem alfabética: cada view sai depois das views que ela lê, e a saída é estável.
     * Views fora da seleção não entram na ordem. Depois separa as que leem procedure, direta ou indiretamente.
     */
    private static Views order(Map<String, View> views) {
        List<View> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String name : new TreeSet<>(views.keySet())) {
            visit(name, views, visited, sorted);
        }
        Set<String> afterRoutines = new LinkedHashSet<>();
        for (View view : sorted) {
            // Na ordem topológica, quem lê uma view que já está à parte também fica à parte.
            if (!view.procedures().isEmpty() || view.relations().stream().anyMatch(afterRoutines::contains)) {
                afterRoutines.add(view.name());
            }
        }
        List<View> before = new ArrayList<>();
        List<View> after = new ArrayList<>();
        for (View view : sorted) {
            (afterRoutines.contains(view.name()) ? after : before).add(view);
        }
        return new Views(before, after);
    }

    // O Firebird não aceita view que dependa de si mesma; o conjunto de visitadas só protege de um catálogo estranho.
    private static void visit(String name, Map<String, View> views, Set<String> visited, List<View> sorted) {
        View view = views.get(name);
        if (view == null || !visited.add(name)) {
            return;
        }
        for (String dependency : view.relations()) {
            visit(dependency, views, visited, sorted);
        }
        sorted.add(view);
    }
}
