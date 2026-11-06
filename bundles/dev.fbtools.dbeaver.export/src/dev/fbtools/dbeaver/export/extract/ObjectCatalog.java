package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Os objetos pequenos do banco, lidos de {@code RDB$*} (ADR 0007): exceptions, roles, collations, BLOB filters, UDFs
 * (functions legadas), mappings locais e a publication padrão. Cada tipo sabe escrever o próprio statement; a ordem
 * entre eles é do {@link MetadataExtractor}.
 */
final class ObjectCatalog {

    /** Nome da única publication do Firebird 5. */
    static final String DEFAULT_PUBLICATION = "RDB$DEFAULT";

    private static final String EXCEPTIONS = """
            SELECT RDB$EXCEPTION_NAME, RDB$MESSAGE FROM RDB$EXCEPTIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY RDB$EXCEPTION_NAME""";

    private static final String ROLES = """
            SELECT RDB$ROLE_NAME, RDB$SYSTEM_PRIVILEGES FROM RDB$ROLES
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY RDB$ROLE_NAME""";

    private static final String COLLATIONS = """
            SELECT c.RDB$COLLATION_NAME, cs.RDB$CHARACTER_SET_NAME, c.RDB$COLLATION_ATTRIBUTES,
                   c.RDB$BASE_COLLATION_NAME, c.RDB$SPECIFIC_ATTRIBUTES, c.RDB$FUNCTION_NAME
              FROM RDB$COLLATIONS c
              JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = c.RDB$CHARACTER_SET_ID
             WHERE COALESCE(c.RDB$SYSTEM_FLAG, 0) = 0 ORDER BY c.RDB$COLLATION_NAME""";

    private static final String FILTERS = """
            SELECT RDB$FUNCTION_NAME, RDB$MODULE_NAME, RDB$ENTRYPOINT, RDB$INPUT_SUB_TYPE, RDB$OUTPUT_SUB_TYPE
              FROM RDB$FILTERS WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY RDB$FUNCTION_NAME""";

    private static final String UDFS = """
            SELECT RDB$FUNCTION_NAME, RDB$MODULE_NAME, RDB$ENTRYPOINT, RDB$RETURN_ARGUMENT FROM RDB$FUNCTIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND COALESCE(RDB$LEGACY_FLAG, 0) = 1
               AND RDB$PACKAGE_NAME IS NULL ORDER BY RDB$FUNCTION_NAME""";

    private static final String UDF_ARGUMENTS = """
            SELECT a.RDB$FUNCTION_NAME, a.RDB$ARGUMENT_POSITION, a.RDB$MECHANISM, a.RDB$FIELD_TYPE,
                   a.RDB$FIELD_SCALE, a.RDB$FIELD_LENGTH, a.RDB$FIELD_SUB_TYPE, a.RDB$FIELD_PRECISION,
                   a.RDB$CHARACTER_LENGTH, cs.RDB$CHARACTER_SET_NAME
              FROM RDB$FUNCTION_ARGUMENTS a
              JOIN RDB$FUNCTIONS f ON f.RDB$FUNCTION_NAME = a.RDB$FUNCTION_NAME AND f.RDB$PACKAGE_NAME IS NULL
              LEFT JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = a.RDB$CHARACTER_SET_ID
             WHERE a.RDB$PACKAGE_NAME IS NULL AND COALESCE(f.RDB$LEGACY_FLAG, 0) = 1
             ORDER BY a.RDB$FUNCTION_NAME, a.RDB$ARGUMENT_POSITION""";

    // Os mappings globais ficam no banco de segurança do servidor, não neste banco: só os locais saem.
    private static final String MAPPINGS = """
            SELECT RDB$MAP_NAME, RDB$MAP_USING, RDB$MAP_PLUGIN, RDB$MAP_DB, RDB$MAP_FROM_TYPE, RDB$MAP_FROM,
                   RDB$MAP_TO_TYPE, RDB$MAP_TO
              FROM RDB$AUTH_MAPPING WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY RDB$MAP_NAME""";

    private static final String PUBLICATION = """
            SELECT RDB$ACTIVE_FLAG, RDB$AUTO_ENABLE FROM RDB$PUBLICATIONS WHERE RDB$PUBLICATION_NAME = 'RDB$DEFAULT'""";

    private static final String PUBLICATION_TABLES = """
            SELECT RDB$TABLE_NAME FROM RDB$PUBLICATION_TABLES WHERE RDB$PUBLICATION_NAME = 'RDB$DEFAULT'
             ORDER BY RDB$TABLE_NAME""";

    // O bit n+1 de RDB$SYSTEM_PRIVILEGES é o n-ésimo privilégio (medido no Firebird 5, 2026-11-03).
    private static final List<String> SYSTEM_PRIVILEGES = List.of("USER_MANAGEMENT", "READ_RAW_PAGES",
            "CREATE_USER_TYPES", "USE_NBACKUP_UTILITY", "CHANGE_SHUTDOWN_MODE", "TRACE_ANY_ATTACHMENT",
            "MONITOR_ANY_ATTACHMENT", "ACCESS_SHUTDOWN_DATABASE", "CREATE_DATABASE", "DROP_DATABASE",
            "USE_GBAK_UTILITY", "USE_GSTAT_UTILITY", "USE_GFIX_UTILITY", "IGNORE_DB_TRIGGERS",
            "CHANGE_HEADER_SETTINGS", "SELECT_ANY_OBJECT_IN_DATABASE", "ACCESS_ANY_OBJECT_IN_DATABASE",
            "MODIFY_ANY_OBJECT_IN_DATABASE", "CHANGE_MAPPING_RULES", "USE_GRANTED_BY_CLAUSE",
            "GRANT_REVOKE_ON_ANY_OBJECT", "GRANT_REVOKE_ANY_DDL_RIGHT", "CREATE_PRIVILEGED_ROLES",
            "GET_DBCRYPT_INFO", "MODIFY_EXT_CONN_POOL", "REPLICATE_INTO_DATABASE", "PROFILE_ANY_ATTACHMENT");

    private final Connection connection;

    ObjectCatalog(Connection connection) {
        this.connection = connection;
    }

    record ExceptionDef(String name, String message) {

        String toSql(ExtractionOptions options) {
            return "CREATE EXCEPTION " + options.quote(name) + " " + literal(message) + ";";
        }
    }

    record Role(String name, long systemPrivileges) {

        String toSql(ExtractionOptions options) {
            String privileges = privilegeNames();
            return "CREATE ROLE " + options.quote(name)
                    + (privileges.isEmpty() ? "" : " SET SYSTEM PRIVILEGES TO " + privileges) + ";";
        }

        private String privilegeNames() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < SYSTEM_PRIVILEGES.size(); i++) {
                if ((systemPrivileges & (1L << (i + 1))) != 0) {
                    names.add(SYSTEM_PRIVILEGES.get(i));
                }
            }
            return String.join(", ", names);
        }
    }

    /**
     * Uma collation do usuário. {@code attributes} é a máscara de {@code RDB$COLLATION_ATTRIBUTES} (1 = {@code PAD
     * SPACE}, 2 = {@code CASE INSENSITIVE}, 4 = {@code ACCENT INSENSITIVE}). Os três ficam sempre explícitos, porque o
     * que a collation base já traz soma ao que se declara; só assim o resultado é exatamente o do banco de origem.
     */
    record Collation(String name, String characterSet, Integer attributes, String base, String specific,
            String externalFunction) {

        String toSql(ExtractionOptions options) {
            StringBuilder sql = new StringBuilder("CREATE COLLATION ").append(options.quote(name)).append(" FOR ")
                    .append(characterSet);
            if (base != null) {
                sql.append(" FROM ").append(options.quote(base));
            } else if (externalFunction != null) {
                sql.append(" FROM EXTERNAL ('").append(externalFunction.replace("'", "''")).append("')");
            }
            if (attributes != null) {
                sql.append((attributes & 1) != 0 ? " PAD SPACE" : " NO PAD")
                        .append((attributes & 2) != 0 ? " CASE INSENSITIVE" : " CASE SENSITIVE")
                        .append((attributes & 4) != 0 ? " ACCENT INSENSITIVE" : " ACCENT SENSITIVE");
            }
            String own = ownSpecificAttributes();
            if (!own.isEmpty()) {
                sql.append(' ').append(literal(own));
            }
            return sql.append(';').toString();
        }

        /** O servidor acrescenta as versões do ICU e da collation; só o que o autor pediu volta ao script. */
        private String ownSpecificAttributes() {
            if (specific == null) {
                return "";
            }
            return java.util.Arrays.stream(specific.split(";"))
                    .filter(part -> !part.isBlank() && !part.startsWith("COLL-VERSION=")
                            && !part.startsWith("ICU-VERSION="))
                    .collect(Collectors.joining(";"));
        }
    }

    record BlobFilter(String name, String module, String entryPoint, int inputType, int outputType) {

        String toSql(ExtractionOptions options) {
            return "DECLARE FILTER " + options.quote(name) + " INPUT_TYPE " + inputType + " OUTPUT_TYPE " + outputType
                    + " ENTRY_POINT " + literal(entryPoint) + " MODULE_NAME " + literal(module) + ";";
        }
    }

    /** Um argumento de UDF: {@code mechanism} negativo é {@code FREE_IT}, 0 é {@code BY VALUE}, 2 {@code BY DESCRIPTOR}. */
    record UdfArgument(int position, int mechanism, FieldType type) {
    }

    /** Uma function legada. {@code returnArgument} 0 é o retorno no argumento 0; senão, {@code RETURNS PARAMETER n}. */
    record Udf(String name, String module, String entryPoint, int returnArgument, List<UdfArgument> arguments) {

        String toSql(ExtractionOptions options, String defaultCharacterSet) {
            StringBuilder sql = new StringBuilder("DECLARE EXTERNAL FUNCTION ").append(options.quote(name));
            List<String> inputs = new ArrayList<>();
            UdfArgument returns = null;
            for (UdfArgument argument : arguments) {
                if (argument.position() == 0) {
                    returns = argument;
                } else {
                    inputs.add(typeSql(argument, defaultCharacterSet)
                            + (argument.mechanism() == 2 ? " BY DESCRIPTOR" : ""));
                }
            }
            if (!inputs.isEmpty()) {
                sql.append('\n').append("    ").append(String.join(", ", inputs));
            }
            sql.append("\nRETURNS ");
            if (returnArgument != 0 || returns == null) {
                sql.append("PARAMETER ").append(returnArgument);
            } else {
                sql.append(typeSql(returns, defaultCharacterSet));
                if (returns.mechanism() == 0) {
                    sql.append(" BY VALUE");
                } else if (returns.mechanism() == 2) {
                    sql.append(" BY DESCRIPTOR");
                } else if (returns.mechanism() < 0) {
                    sql.append(" FREE_IT");
                }
            }
            return sql.append("\nENTRY_POINT ").append(literal(entryPoint)).append(" MODULE_NAME ")
                    .append(literal(module)).append(';').toString();
        }

        private static String typeSql(UdfArgument argument, String defaultCharacterSet) {
            FieldType type = argument.type();
            if (type.type() == 40) {
                int length = type.charLength() != null && type.charLength() > 0 ? type.charLength() : type.length();
                String charset = type.characterSet() == null || type.characterSet().equals(defaultCharacterSet) ? ""
                        : " CHARACTER SET " + type.characterSet();
                return "CSTRING(" + length + ")" + charset;
            }
            if (type.type() == 261) {
                return "BLOB";
            }
            return type.toSql(defaultCharacterSet, false);
        }
    }

    /** Um mapping local; {@code using} é {@code RDB$MAP_USING} ({@code P} plugin, {@code M} outro mapping). */
    record Mapping(String name, String using, String plugin, String database, String fromType, String from,
            int toType, String to) {

        /** O statement, ou {@code null} se o modo de autenticação não é conhecido. */
        String toSql(ExtractionOptions options) {
            String usingSql = switch (using) {
                case "P" -> plugin == null ? "ANY PLUGIN" : "PLUGIN " + options.quote(plugin);
                case "M" -> "MAPPING";
                default -> null;
            };
            if (usingSql == null) {
                return null;
            }
            return "CREATE MAPPING " + options.quote(name) + " USING " + usingSql
                    + (database == null ? "" : " IN " + options.quote(database)) + " FROM "
                    + ("*".equals(from) ? "ANY " + fromType : fromType + " " + options.quote(from)) + " TO "
                    + (toType == 1 ? "ROLE" : "USER") + (to == null ? "" : " " + options.quote(to)) + ";";
        }
    }

    /** A publication padrão: ligada ou não, se inclui as tabelas novas sozinha e quais tabelas inclui. */
    record Publication(boolean active, boolean autoEnable, Set<String> tables) {

        boolean configured() {
            return active || autoEnable || !tables.isEmpty();
        }
    }

    List<String> listExceptionNames() throws SQLException {
        return readExceptions(Selection.all()).stream().map(ExceptionDef::name).toList();
    }

    List<String> listRoleNames() throws SQLException {
        return readRoles(Selection.all()).stream().map(Role::name).toList();
    }

    List<String> listCollationNames() throws SQLException {
        return readCollations(Selection.all()).stream().map(Collation::name).toList();
    }

    List<String> listFilterNames() throws SQLException {
        return readFilters(Selection.all()).stream().map(BlobFilter::name).toList();
    }

    List<String> listUdfNames() throws SQLException {
        return readUdfs(Selection.all()).stream().map(Udf::name).toList();
    }

    List<String> listMappingNames() {
        return readMappings(Selection.all()).stream().map(Mapping::name).toList();
    }

    List<String> listPublicationNames() {
        Publication publication = readPublication();
        return publication != null && publication.configured() ? List.of(DEFAULT_PUBLICATION) : List.of();
    }

    List<ExceptionDef> readExceptions(Selection selection) throws SQLException {
        List<ExceptionDef> exceptions = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(EXCEPTIONS)) {
            while (rs.next()) {
                String name = rs.getString(1).strip();
                if (selection.includesException(name)) {
                    exceptions.add(new ExceptionDef(name, rs.getString(2)));
                }
            }
        }
        return exceptions;
    }

    List<Role> readRoles(Selection selection) throws SQLException {
        List<Role> roles = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(ROLES)) {
            while (rs.next()) {
                String name = rs.getString(1).strip();
                if (selection.includesRole(name)) {
                    roles.add(new Role(name, littleEndian(rs.getBytes(2))));
                }
            }
        }
        return roles;
    }

    List<Collation> readCollations(Selection selection) throws SQLException {
        List<Collation> collations = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(COLLATIONS)) {
            while (rs.next()) {
                String name = rs.getString(1).strip();
                if (selection.includesCollation(name)) {
                    collations.add(new Collation(name, rs.getString(2).strip(), Rdb.nullableInt(rs,
                            "RDB$COLLATION_ATTRIBUTES"), Rdb.trimmed(rs.getString(4)), Rdb.trimmed(rs.getString(5)),
                            Rdb.trimmed(rs.getString(6))));
                }
            }
        }
        return collations;
    }

    List<BlobFilter> readFilters(Selection selection) throws SQLException {
        List<BlobFilter> filters = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(FILTERS)) {
            while (rs.next()) {
                String name = rs.getString(1).strip();
                if (selection.includesFilter(name)) {
                    filters.add(new BlobFilter(name, rs.getString(2).strip(), rs.getString(3).strip(), rs.getInt(4),
                            rs.getInt(5)));
                }
            }
        }
        return filters;
    }

    List<Udf> readUdfs(Selection selection) throws SQLException {
        Map<String, List<UdfArgument>> arguments = new TreeMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(UDF_ARGUMENTS)) {
            while (rs.next()) {
                FieldType type = new FieldType(rs.getInt(4), rs.getInt(7), rs.getInt(6), Rdb.nullableInt(rs,
                        "RDB$CHARACTER_LENGTH"), Rdb.nullableInt(rs, "RDB$FIELD_PRECISION"), rs.getInt(5), null,
                        Rdb.trimmed(rs.getString(10)), null, null);
                arguments.computeIfAbsent(rs.getString(1).strip(), k -> new ArrayList<>())
                        .add(new UdfArgument(rs.getInt(2), rs.getInt(3), type));
            }
        }
        List<Udf> udfs = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(UDFS)) {
            while (rs.next()) {
                String name = rs.getString(1).strip();
                if (selection.includesUdf(name)) {
                    udfs.add(new Udf(name, rs.getString(2).strip(), rs.getString(3).strip(), rs.getInt(4),
                            arguments.getOrDefault(name, List.of())));
                }
            }
        }
        return udfs;
    }

    /** Sem acesso a {@code RDB$AUTH_MAPPING} (banco antigo ou usuário sem permissão), não há mappings a extrair. */
    List<Mapping> readMappings(Selection selection) {
        List<Mapping> mappings = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(MAPPINGS)) {
            while (rs.next()) {
                String name = rs.getString(1).strip();
                if (selection.includesMapping(name)) {
                    mappings.add(new Mapping(name, Rdb.trimmed(rs.getString(2)), Rdb.trimmed(rs.getString(3)),
                            Rdb.trimmed(rs.getString(4)), Rdb.trimmed(rs.getString(5)), Rdb.trimmed(rs.getString(6)),
                            rs.getInt(7), Rdb.trimmed(rs.getString(8))));
                }
            }
        } catch (SQLException e) {
            return List.of();
        }
        return mappings;
    }

    /** {@code null} se o banco não tem publications (anterior ao Firebird 4). */
    Publication readPublication() {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(PUBLICATION)) {
            if (!rs.next()) {
                return null;
            }
            boolean active = rs.getInt(1) == 1;
            boolean autoEnable = rs.getInt(2) == 1;
            Set<String> tables = new java.util.LinkedHashSet<>();
            try (Statement tableStatement = connection.createStatement();
                    ResultSet tableRs = tableStatement.executeQuery(PUBLICATION_TABLES)) {
                while (tableRs.next()) {
                    tables.add(tableRs.getString(1).strip());
                }
            }
            return new Publication(active, autoEnable, tables);
        } catch (SQLException e) {
            return null;
        }
    }

    private static long littleEndian(byte[] bytes) {
        long value = 0;
        if (bytes != null) {
            for (int i = Math.min(bytes.length, 8) - 1; i >= 0; i--) {
                value = (value << 8) | (bytes[i] & 0xFF);
            }
        }
        return value;
    }

    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }
}
