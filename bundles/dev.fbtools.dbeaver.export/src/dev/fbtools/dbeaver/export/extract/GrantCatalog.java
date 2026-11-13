package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Os privilégios de {@code RDB$USER_PRIVILEGES}: {@code GRANT} de tabela e view (privilégios de linha e de coluna),
 * {@code EXECUTE} de procedure, function e package, {@code USAGE} de exception e generator, e
 * {@code GRANT <role> TO}. Ficam de fora os privilégios do dono sobre o que criou (o servidor os dá sozinho) e os de
 * sistema; os privilégios de DDL ({@code CREATE TABLE} etc.) a extração ainda não gera, e avisa.
 */
final class GrantCatalog {

    // RDB$GRANTOR nulo marca os privilégios de sistema; usuário igual a quem concede, o dono sobre o que criou.
    private static final String PRIVILEGES = """
            SELECT RDB$USER, RDB$USER_TYPE, RDB$PRIVILEGE, RDB$GRANT_OPTION, RDB$RELATION_NAME, RDB$FIELD_NAME,
                   RDB$OBJECT_TYPE
              FROM RDB$USER_PRIVILEGES
             WHERE RDB$GRANTOR IS NOT NULL AND (RDB$PRIVILEGE = 'M' OR RDB$USER <> RDB$GRANTOR)
             ORDER BY RDB$OBJECT_TYPE, RDB$RELATION_NAME, RDB$USER_TYPE, RDB$USER, RDB$GRANT_OPTION, RDB$PRIVILEGE,
                      RDB$FIELD_NAME""";

    /** Tabela ou view: privilégios nas linhas e nas colunas. */
    private static final int OBJECT_RELATION = 0;

    private final Connection connection;

    GrantCatalog(Connection connection) {
        this.connection = connection;
    }

    /** Um destino dos privilégios: o tipo de {@code RDB$USER_TYPE} e o nome. */
    private record Grantee(int type, String name) {
    }

    /** O que se concede, para agrupar: quem recebe, em que objeto e com que opção ({@code RDB$GRANT_OPTION}). */
    private record Key(Grantee grantee, int objectType, String object, int option) {
    }

    private static final class Privileges {
        final TreeSet<String> rows = new TreeSet<>();
        final Map<String, TreeSet<String>> columns = new LinkedHashMap<>();
        String single;
    }

    /**
     * Os {@code GRANT}s prontos. Com {@code options.grantsOnlySelected()}, só os que têm o objeto e o destinatário na
     * seleção (usuários e {@code PUBLIC} não são objetos e sempre valem); um destinatário fora dela deixaria o script
     * falhar, que ele não existiria no banco novo.
     */
    List<String> read(Selection selection, ExtractionOptions options, ExtractionProgress progress)
            throws SQLException {
        Map<Key, Privileges> grants = new LinkedHashMap<>();
        TreeSet<String> unsupported = new TreeSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(PRIVILEGES)) {
            while (rs.next()) {
                progress.checkCancelled();
                String privilege = rs.getString("RDB$PRIVILEGE").strip();
                String object = rs.getString("RDB$RELATION_NAME").strip();
                int objectType = rs.getInt("RDB$OBJECT_TYPE");
                String field = Rdb.trimmed(rs.getString("RDB$FIELD_NAME"));
                Grantee grantee = new Grantee(rs.getInt("RDB$USER_TYPE"), rs.getString("RDB$USER").strip());
                boolean membership = privilege.equals("M");
                if (!selected(selection, options, membership ? "role" : kind(objectType), object)
                        || !granteeSelected(selection, options, grantee)) {
                    continue;
                }
                if (granteeSql(grantee, options) == null) {
                    unsupported.add(privilege + " em " + object + " para " + grantee.name() + " (tipo "
                            + grantee.type() + ")");
                    continue;
                }
                if (membership) {
                    grants.computeIfAbsent(new Key(grantee, -1, object, rs.getInt("RDB$GRANT_OPTION")),
                            key -> new Privileges()).single = "M";
                    continue;
                }
                if (objectSql(objectType, privilege, options, object) == null) {
                    unsupported.add(privilege + " em " + object);
                    continue;
                }
                Privileges entry = grants.computeIfAbsent(new Key(grantee, objectType, object,
                        rs.getInt("RDB$GRANT_OPTION")), key -> new Privileges());
                if (objectType == OBJECT_RELATION && field != null) {
                    entry.columns.computeIfAbsent(privilege, p -> new TreeSet<>()).add(field);
                } else if (objectType == OBJECT_RELATION) {
                    entry.rows.add(privilege);
                } else {
                    entry.single = privilege;
                }
            }
        }
        if (!unsupported.isEmpty()) {
            progress.warn("Privilégios que a extração não gera e que ficaram fora do script: "
                    + String.join(", ", unsupported) + ".");
        }
        List<String> statements = new ArrayList<>();
        for (Map.Entry<Key, Privileges> grant : grants.entrySet()) {
            statements.add(toSql(grant.getKey(), grant.getValue(), options));
        }
        return statements;
    }

    private static String toSql(Key key, Privileges privileges, ExtractionOptions options) {
        String grantee = granteeSql(key.grantee(), options);
        if (key.objectType() == -1) {
            return "GRANT " + options.quote(key.object()) + " TO " + grantee
                    + (key.option() == 2 ? " WITH ADMIN OPTION" : "") + ";";
        }
        String suffix = key.option() == 1 ? " WITH GRANT OPTION" : "";
        if (key.objectType() != OBJECT_RELATION) {
            String verb = privileges.single.equals("X") ? "EXECUTE" : "USAGE";
            return "GRANT " + verb + " ON " + objectSql(key.objectType(), privileges.single, options, key.object())
                    + " TO " + grantee + suffix + ";";
        }
        List<String> list = new ArrayList<>();
        if (privileges.rows.containsAll(List.of("S", "I", "U", "D", "R"))) {
            list.add("ALL");
        } else {
            for (String privilege : List.of("S", "I", "U", "D", "R")) {
                if (privileges.rows.contains(privilege)) {
                    list.add(word(privilege));
                }
            }
        }
        for (String privilege : List.of("U", "R")) {
            TreeSet<String> columns = privileges.columns.get(privilege);
            if (columns != null) {
                list.add(word(privilege) + " (" + String.join(", ", columns.stream().map(options::quote).toList())
                        + ")");
            }
        }
        return "GRANT " + String.join(", ", list) + " ON " + options.quote(key.object()) + " TO " + grantee + suffix
                + ";";
    }

    private static String word(String privilege) {
        return switch (privilege) {
            case "S" -> "SELECT";
            case "I" -> "INSERT";
            case "U" -> "UPDATE";
            case "D" -> "DELETE";
            default -> "REFERENCES";
        };
    }

    /** {@code null} se o destinatário é de um tipo que a extração não conhece. */
    private static String granteeSql(Grantee grantee, ExtractionOptions options) {
        String name = options.quote(grantee.name());
        return switch (grantee.type()) {
            case 8 -> grantee.name().equals("PUBLIC") ? "PUBLIC" : "USER " + name;
            case 13 -> "ROLE " + name;
            case 1 -> "VIEW " + name;
            case 2 -> "TRIGGER " + name;
            case 5 -> "PROCEDURE " + name;
            case 15 -> "FUNCTION " + name;
            case 18 -> "PACKAGE " + name;
            default -> null;
        };
    }

    /** O objeto como vai no {@code ON} ({@code PROCEDURE X}, {@code X} para tabela e view), ou {@code null}. */
    private static String objectSql(int objectType, String privilege, ExtractionOptions options, String object) {
        String name = options.quote(object);
        if (objectType == OBJECT_RELATION) {
            return List.of("S", "I", "U", "D", "R").contains(privilege) ? name : null;
        }
        return switch (objectType) {
            case 5 -> privilege.equals("X") ? "PROCEDURE " + name : null;
            case 15 -> privilege.equals("X") ? "FUNCTION " + name : null;
            case 18 -> privilege.equals("X") ? "PACKAGE " + name : null;
            case 7 -> privilege.equals("G") ? "EXCEPTION " + name : null;
            case 14 -> privilege.equals("G") ? "GENERATOR " + name : null;
            default -> null;
        };
    }

    private static String kind(int objectType) {
        return switch (objectType) {
            case OBJECT_RELATION -> "relation";
            case 5 -> "procedure";
            case 15 -> "function";
            case 18 -> "package";
            case 7 -> "exception";
            case 14 -> "generator";
            default -> null;
        };
    }

    private static boolean granteeSelected(Selection selection, ExtractionOptions options, Grantee grantee) {
        if (!options.grantsOnlySelected()) {
            return true;
        }
        return switch (grantee.type()) {
            case 13 -> selection.includesRole(grantee.name());
            case 1 -> selection.includesView(grantee.name());
            case 2 -> selection.includesTrigger(grantee.name());
            case 5 -> selection.includesProcedure(grantee.name());
            case 15 -> selection.includesFunction(grantee.name()) || selection.includesUdf(grantee.name());
            case 18 -> selection.includesPackage(grantee.name());
            default -> true;
        };
    }

    private static boolean selected(Selection selection, ExtractionOptions options, String kind, String name) {
        if (!options.grantsOnlySelected()) {
            return true;
        }
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            case "relation" -> selection.includesTable(name) || selection.includesView(name);
            case "procedure" -> selection.includesProcedure(name);
            case "function" -> selection.includesFunction(name) || selection.includesUdf(name);
            case "package" -> selection.includesPackage(name);
            case "exception" -> selection.includesException(name);
            case "generator" -> selection.includesGenerator(name);
            case "role" -> selection.includesRole(name);
            default -> true;
        };
    }
}
