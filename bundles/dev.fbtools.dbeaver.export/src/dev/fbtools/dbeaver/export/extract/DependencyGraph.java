package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Os Objetos relacionados: de que cada objeto depende, lido de {@code RDB$DEPENDENCIES} (ADR 0007) e das FKs. É lido
 * uma vez e consultado sem banco, para o "Add related objects" do wizard. A dependência é transitiva, e só vale
 * para objetos que a extração sabe escrever (domínios, generators, exceptions, UDFs, collations, tabelas, views,
 * rotinas, packages e triggers).
 */
public final class DependencyGraph {

    enum Kind {
        DOMAIN, GENERATOR, EXCEPTION, UDF, COLLATION, TABLE, VIEW, PROCEDURE, FUNCTION, PACKAGE, TRIGGER
    }

    record Node(Kind kind, String name) {
    }

    // Tipos: 0 tabela, 1 view, 2 trigger, 3 campo calculado, 5 procedure, 6 índice, 7 exception, 9 campo/domínio,
    // 14 generator, 15 function (ou UDF), 17 collation, 18 cabeçalho de package, 19 corpo de package.
    private static final String DEPENDENCIES = """
            SELECT DISTINCT RDB$DEPENDENT_NAME, RDB$DEPENDENT_TYPE, RDB$DEPENDED_ON_NAME, RDB$DEPENDED_ON_TYPE,
                   RDB$FIELD_NAME, RDB$PACKAGE_NAME
              FROM RDB$DEPENDENCIES
             WHERE RDB$DEPENDENT_TYPE IN (0, 1, 2, 3, 5, 6, 9, 15, 18, 19)
               AND RDB$DEPENDED_ON_TYPE IN (0, 1, 5, 7, 9, 14, 15, 17, 18)""";

    /** Campos anônimos ({@code RDB$nnn}) de colunas calculadas -> tabela. */
    private static final String COMPUTED_OWNERS = """
            SELECT RDB$FIELD_SOURCE, RDB$RELATION_NAME FROM RDB$RELATION_FIELDS
             WHERE RDB$FIELD_SOURCE STARTING WITH 'RDB$' AND COALESCE(RDB$SYSTEM_FLAG, 0) = 0""";

    /** Triggers que são {@code CHECK} de tabela -> tabela. */
    private static final String CHECK_OWNERS = """
            SELECT cc.RDB$TRIGGER_NAME, rc.RDB$RELATION_NAME
              FROM RDB$CHECK_CONSTRAINTS cc
              JOIN RDB$RELATION_CONSTRAINTS rc ON rc.RDB$CONSTRAINT_NAME = cc.RDB$CONSTRAINT_NAME""";

    private static final String INDEX_OWNERS = "SELECT RDB$INDEX_NAME, RDB$RELATION_NAME FROM RDB$INDICES";

    private final Map<Node, Set<Node>> dependsOn;
    private final Map<String, Set<String>> foreignKeys;
    private final Map<Kind, Set<String>> known;

    private DependencyGraph(Map<Node, Set<Node>> dependsOn, Map<String, Set<String>> foreignKeys,
            Map<Kind, Set<String>> known) {
        this.dependsOn = dependsOn;
        this.foreignKeys = foreignKeys;
        this.known = known;
    }

    /** Lê as dependências do banco. */
    public static DependencyGraph read(Connection connection) throws SQLException {
        MetadataExtractor lists = new MetadataExtractor(connection);
        return read(connection, new KnownObjects(lists.listDomains(), lists.listGenerators(),
                lists.listExceptions(), lists.listUdfs(), lists.listCollations(), lists.listTables(),
                lists.listViews(), lists.listProcedures(), lists.listFunctions(), lists.listPackages(),
                lists.listTriggers()));
    }

    /** Os nomes dos objetos que a extração sabe escrever, como quem chama já os leu. */
    public record KnownObjects(List<String> domains, List<String> generators, List<String> exceptions,
            List<String> udfs, List<String> collations, List<String> tables, List<String> views,
            List<String> procedures, List<String> functions, List<String> packages, List<String> triggers) {
    }

    /** Lê as dependências do banco, sem listar de novo os objetos que quem chama já tem. */
    public static DependencyGraph read(Connection connection, KnownObjects objects) throws SQLException {
        Names names = new Names(new HashSet<>(objects.domains()), new HashSet<>(objects.views()),
                new HashSet<>(objects.functions()), new HashSet<>(objects.udfs()),
                new HashSet<>(objects.triggers()), new HashSet<>(objects.tables()));
        Map<String, String> computed = owners(connection, COMPUTED_OWNERS);
        Map<String, String> checks = owners(connection, CHECK_OWNERS);
        Map<String, String> indexes = owners(connection, INDEX_OWNERS);
        Map<Node, Set<Node>> dependsOn = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(DEPENDENCIES)) {
            while (rs.next()) {
                Node dependent = dependent(rs.getString(1).strip(), rs.getInt(2), names, computed, checks, indexes);
                Node target = target(rs.getString(3).strip(), rs.getInt(4), Rdb.trimmed(rs.getString(5)),
                        Rdb.trimmed(rs.getString(6)), names);
                if (dependent != null && target != null && !dependent.equals(target)) {
                    dependsOn.computeIfAbsent(dependent, k -> new TreeSet<>(NODE_ORDER)).add(target);
                }
            }
        }
        // A FK não está em RDB$DEPENDENCIES: a tabela filha precisa da tabela referenciada.
        // Coluna e parâmetro que usam um domínio não ficam em RDB$DEPENDENCIES: o domínio está no RDB$FIELD_SOURCE.
        domainUsers(connection, COLUMN_DOMAINS, names, dependsOn, true);
        domainUsers(connection, PARAMETER_DOMAINS, names, dependsOn, false);
        Map<String, Set<String>> foreignKeys = ForeignKeyOrder.read(connection);
        foreignKeys.forEach((child, parents) -> parents.forEach(parent -> dependsOn
                .computeIfAbsent(new Node(Kind.TABLE, child), k -> new TreeSet<>(NODE_ORDER))
                .add(new Node(Kind.TABLE, parent))));
        Map<Kind, Set<String>> known = new EnumMap<>(Kind.class);
        known.put(Kind.DOMAIN, names.domains());
        known.put(Kind.GENERATOR, new HashSet<>(objects.generators()));
        known.put(Kind.EXCEPTION, new HashSet<>(objects.exceptions()));
        known.put(Kind.UDF, names.udfs());
        known.put(Kind.COLLATION, new HashSet<>(objects.collations()));
        known.put(Kind.TABLE, names.tables());
        known.put(Kind.VIEW, names.views());
        known.put(Kind.PROCEDURE, new HashSet<>(objects.procedures()));
        known.put(Kind.FUNCTION, names.functions());
        known.put(Kind.PACKAGE, new HashSet<>(objects.packages()));
        known.put(Kind.TRIGGER, names.triggers());
        return new DependencyGraph(dependsOn, foreignKeys, known);
    }

    private static final java.util.Comparator<Node> NODE_ORDER = java.util.Comparator.comparing(Node::kind)
            .thenComparing(Node::name);

    private record Names(Set<String> domains, Set<String> views, Set<String> functions, Set<String> udfs,
            Set<String> triggers, Set<String> tables) {
    }

    private static final String COLUMN_DOMAINS = "SELECT RDB$RELATION_NAME, RDB$FIELD_SOURCE FROM RDB$RELATION_FIELDS "
            + "WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0";

    /** Parâmetros de procedures e functions, soltas ou de package; o terceiro campo é o package, se houver. */
    private static final String PARAMETER_DOMAINS = """
            SELECT RDB$PROCEDURE_NAME, RDB$FIELD_SOURCE, RDB$PACKAGE_NAME, 'P' FROM RDB$PROCEDURE_PARAMETERS
            UNION
            SELECT RDB$FUNCTION_NAME, RDB$FIELD_SOURCE, RDB$PACKAGE_NAME, 'F' FROM RDB$FUNCTION_ARGUMENTS""";

    private static void domainUsers(Connection connection, String sql, Names names, Map<Node, Set<Node>> dependsOn,
            boolean relations) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String domain = rs.getString(2).strip();
                if (!names.domains().contains(domain)) {
                    continue;
                }
                String name = rs.getString(1).strip();
                Node user;
                if (relations) {
                    user = relation(name, names);
                } else if (rs.getString(3) != null) {
                    user = new Node(Kind.PACKAGE, rs.getString(3).strip());
                } else {
                    user = new Node("P".equals(rs.getString(4)) ? Kind.PROCEDURE : Kind.FUNCTION, name);
                }
                dependsOn.computeIfAbsent(user, k -> new TreeSet<>(NODE_ORDER)).add(new Node(Kind.DOMAIN, domain));
            }
        }
    }

    private static Map<String, String> owners(Connection connection, String sql) throws SQLException {
        Map<String, String> owners = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                owners.put(rs.getString(1).strip(), rs.getString(2).strip());
            }
        }
        return owners;
    }

    private static Node dependent(String name, int type, Names names, Map<String, String> computed,
            Map<String, String> checks, Map<String, String> indexes) {
        return switch (type) {
            case 0, 1 -> relation(name, names);
            case 2 -> checks.containsKey(name) ? new Node(Kind.TABLE, checks.get(name))
                    : names.triggers().contains(name) ? new Node(Kind.TRIGGER, name) : null;
            case 3 -> computed.containsKey(name) ? relation(computed.get(name), names) : null;
            case 5 -> new Node(Kind.PROCEDURE, name);
            // Domínio cujo CHECK/DEFAULT usa generator, exception ou function.
            case 9 -> names.domains().contains(name) ? new Node(Kind.DOMAIN, name) : null;
            case 6 -> indexes.containsKey(name) ? relation(indexes.get(name), names) : null;
            case 15 -> new Node(Kind.FUNCTION, name);
            case 18, 19 -> new Node(Kind.PACKAGE, name);
            default -> null;
        };
    }

    private static Node target(String name, int type, String field, String packageName, Names names) {
        return switch (type) {
            case 0, 1 -> relation(name, names);
            case 5 -> new Node(Kind.PROCEDURE, name);
            case 7 -> new Node(Kind.EXCEPTION, name);
            // Com o campo preenchido é a coluna de uma tabela; sem ele, o domínio.
            case 9 -> field != null ? relation(name, names)
                    : names.domains().contains(name) ? new Node(Kind.DOMAIN, name) : null;
            case 14 -> new Node(Kind.GENERATOR, name);
            case 15 -> packageName != null ? new Node(Kind.PACKAGE, packageName)
                    : names.udfs().contains(name) ? new Node(Kind.UDF, name)
                    : names.functions().contains(name) ? new Node(Kind.FUNCTION, name) : null;
            case 17 -> new Node(Kind.COLLATION, name);
            case 18 -> new Node(Kind.PACKAGE, name);
            default -> null;
        };
    }

    private static Node relation(String name, Names names) {
        return new Node(names.views().contains(name) ? Kind.VIEW : Kind.TABLE, name);
    }

    /**
     * A seleção mais tudo de que ela depende, transitivamente. Um tipo que a seleção já tem por inteiro
     * ({@code null}) fica como está; nos demais entram os objetos que faltavam. Objetos que a extração não lista
     * (de sistema, por exemplo) não entram.
     */
    public Selection related(Selection selection) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<Node> seen = new LinkedHashSet<>();
        start(queue, seen, Kind.DOMAIN, selection.domains());
        start(queue, seen, Kind.GENERATOR, selection.generators());
        start(queue, seen, Kind.EXCEPTION, selection.exceptions());
        start(queue, seen, Kind.UDF, selection.udfs());
        start(queue, seen, Kind.COLLATION, selection.collations());
        start(queue, seen, Kind.TABLE, selection.tables());
        start(queue, seen, Kind.VIEW, selection.views());
        start(queue, seen, Kind.PROCEDURE, selection.procedures());
        start(queue, seen, Kind.FUNCTION, selection.functions());
        start(queue, seen, Kind.PACKAGE, selection.packages());
        start(queue, seen, Kind.TRIGGER, selection.triggers());
        while (!queue.isEmpty()) {
            for (Node target : dependsOn.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(target)) {
                    queue.add(target);
                }
            }
        }
        return new Selection(plus(selection.domains(), Kind.DOMAIN, seen), plus(selection.generators(),
                Kind.GENERATOR, seen), plus(selection.tables(), Kind.TABLE, seen), selection.indexes(),
                plus(selection.views(), Kind.VIEW, seen), plus(selection.procedures(), Kind.PROCEDURE, seen),
                plus(selection.functions(), Kind.FUNCTION, seen), plus(selection.packages(), Kind.PACKAGE, seen),
                plus(selection.exceptions(), Kind.EXCEPTION, seen), plus(selection.udfs(), Kind.UDF, seen),
                plus(selection.collations(), Kind.COLLATION, seen), selection.filters(), selection.roles(),
                plus(selection.triggers(), Kind.TRIGGER, seen), selection.publications(), selection.mappings());
    }

    /** As tabelas dadas mais as que as FKs delas referenciam, transitivamente (para a aba Data). */
    public Set<String> tablesWithParents(Collection<String> tables) {
        Set<String> result = new LinkedHashSet<>(tables);
        ArrayDeque<String> queue = new ArrayDeque<>(result);
        while (!queue.isEmpty()) {
            for (String parent : foreignKeys.getOrDefault(queue.poll(), Set.of())) {
                if (result.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return result;
    }

    private static void start(ArrayDeque<Node> queue, Set<Node> seen, Kind kind, Set<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            Node node = new Node(kind, name);
            if (seen.add(node)) {
                queue.add(node);
            }
        }
    }

    private Set<String> plus(Set<String> chosen, Kind kind, Set<Node> seen) {
        if (chosen == null) {
            return null;
        }
        Set<String> all = new TreeSet<>(chosen);
        for (Node node : seen) {
            if (node.kind() == kind && known.get(kind).contains(node.name())) {
                all.add(node.name());
            }
        }
        return all;
    }
}
