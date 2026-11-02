package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Procedures, functions e packages lidos de {@code RDB$*} (ADR 0007). Cada rotina tem duas peças: o Cabeçalho vazio
 * (assinatura e um corpo que não faz nada), que pode ser criado antes de tudo o que depende dela, e o corpo, que vira
 * {@code ALTER} depois. O corpo é a Fonte do banco exatamente como está. Rotinas de package não saem à parte: o
 * package carrega o header e o body inteiros. Functions legadas (UDFs) ficam para o issue 06.
 */
final class RoutineCatalog {

    private static final String PROCEDURES = """
            SELECT RDB$PROCEDURE_NAME, RDB$PROCEDURE_SOURCE, RDB$PROCEDURE_TYPE, RDB$ENGINE_NAME, RDB$ENTRYPOINT,
                   RDB$SQL_SECURITY
              FROM RDB$PROCEDURES
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$PACKAGE_NAME IS NULL ORDER BY RDB$PROCEDURE_NAME""";

    private static final String FUNCTIONS = """
            SELECT RDB$FUNCTION_NAME, RDB$FUNCTION_SOURCE, RDB$RETURN_ARGUMENT, RDB$DETERMINISTIC_FLAG,
                   RDB$ENGINE_NAME, RDB$ENTRYPOINT, RDB$SQL_SECURITY
              FROM RDB$FUNCTIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND COALESCE(RDB$LEGACY_FLAG, 0) = 0
               AND RDB$PACKAGE_NAME IS NULL ORDER BY RDB$FUNCTION_NAME""";

    private static final String PACKAGES = """
            SELECT RDB$PACKAGE_NAME, RDB$PACKAGE_HEADER_SOURCE, RDB$PACKAGE_BODY_SOURCE, RDB$SQL_SECURITY
              FROM RDB$PACKAGES WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY RDB$PACKAGE_NAME""";

    // O tipo de cada parâmetro: o domínio (campo de nome do usuário) ou o campo anônimo RDB$nnn, com charset e collation.
    private static final String PROCEDURE_PARAMETERS = """
            SELECT p.RDB$PROCEDURE_NAME AS OWNER, p.RDB$PARAMETER_NAME AS NAME, p.RDB$PARAMETER_TYPE AS DIRECTION,
                   p.RDB$FIELD_SOURCE, p.RDB$NULL_FLAG, p.RDB$DEFAULT_SOURCE, p.RDB$PARAMETER_MECHANISM AS MECHANISM,
                   p.RDB$RELATION_NAME AS TYPE_RELATION, p.RDB$FIELD_NAME AS TYPE_COLUMN,
                   f.RDB$FIELD_TYPE, f.RDB$FIELD_SUB_TYPE, f.RDB$FIELD_LENGTH, f.RDB$CHARACTER_LENGTH,
                   f.RDB$FIELD_PRECISION, f.RDB$FIELD_SCALE, f.RDB$SEGMENT_LENGTH,
                   cs.RDB$CHARACTER_SET_NAME, co.RDB$COLLATION_NAME
              FROM RDB$PROCEDURE_PARAMETERS p
              JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = p.RDB$FIELD_SOURCE
              LEFT JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
              LEFT JOIN RDB$COLLATIONS co ON co.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
                                         AND co.RDB$COLLATION_ID = f.RDB$COLLATION_ID
             WHERE p.RDB$PACKAGE_NAME IS NULL
             ORDER BY p.RDB$PROCEDURE_NAME, p.RDB$PARAMETER_TYPE, p.RDB$PARAMETER_NUMBER""";

    private static final String FUNCTION_ARGUMENTS = """
            SELECT a.RDB$FUNCTION_NAME AS OWNER, a.RDB$ARGUMENT_NAME AS NAME, a.RDB$ARGUMENT_POSITION AS ARG_POSITION,
                   a.RDB$FIELD_SOURCE, a.RDB$NULL_FLAG, a.RDB$DEFAULT_SOURCE, a.RDB$ARGUMENT_MECHANISM AS MECHANISM,
                   a.RDB$RELATION_NAME AS TYPE_RELATION, a.RDB$FIELD_NAME AS TYPE_COLUMN,
                   f.RDB$FIELD_TYPE, f.RDB$FIELD_SUB_TYPE, f.RDB$FIELD_LENGTH, f.RDB$CHARACTER_LENGTH,
                   f.RDB$FIELD_PRECISION, f.RDB$FIELD_SCALE, f.RDB$SEGMENT_LENGTH,
                   cs.RDB$CHARACTER_SET_NAME, co.RDB$COLLATION_NAME
              FROM RDB$FUNCTION_ARGUMENTS a
              JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = a.RDB$FIELD_SOURCE
              LEFT JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
              LEFT JOIN RDB$COLLATIONS co ON co.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
                                         AND co.RDB$COLLATION_ID = f.RDB$COLLATION_ID
             WHERE a.RDB$PACKAGE_NAME IS NULL
             ORDER BY a.RDB$FUNCTION_NAME, a.RDB$ARGUMENT_POSITION""";

    /** Quem usa uma rotina fora das views: coluna calculada, índice de expressão ou check de uma tabela. */
    private static final String TABLE_USERS = """
            SELECT DISTINCT rf.RDB$RELATION_NAME AS TABLE_NAME, 'coluna ' || TRIM(rf.RDB$RELATION_NAME) || '.'
                   || TRIM(rf.RDB$FIELD_NAME) AS USER_NAME, d.RDB$DEPENDED_ON_NAME, d.RDB$DEPENDED_ON_TYPE,
                   d.RDB$PACKAGE_NAME
              FROM RDB$DEPENDENCIES d
              JOIN RDB$RELATION_FIELDS rf ON rf.RDB$FIELD_SOURCE = d.RDB$DEPENDENT_NAME
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = rf.RDB$RELATION_NAME AND r.RDB$VIEW_BLR IS NULL
             WHERE d.RDB$DEPENDENT_TYPE = 3 AND d.RDB$DEPENDED_ON_TYPE IN (5, 15, 18)
            UNION
            SELECT i.RDB$RELATION_NAME, 'índice ' || TRIM(i.RDB$INDEX_NAME), d.RDB$DEPENDED_ON_NAME,
                   d.RDB$DEPENDED_ON_TYPE, d.RDB$PACKAGE_NAME
              FROM RDB$DEPENDENCIES d
              JOIN RDB$INDICES i ON i.RDB$INDEX_NAME = d.RDB$DEPENDENT_NAME
             WHERE d.RDB$DEPENDENT_TYPE = 6 AND d.RDB$DEPENDED_ON_TYPE IN (5, 15, 18)
            UNION
            SELECT rc.RDB$RELATION_NAME, 'check ' || TRIM(rc.RDB$CONSTRAINT_NAME), d.RDB$DEPENDED_ON_NAME,
                   d.RDB$DEPENDED_ON_TYPE, d.RDB$PACKAGE_NAME
              FROM RDB$DEPENDENCIES d
              JOIN RDB$CHECK_CONSTRAINTS cc ON cc.RDB$TRIGGER_NAME = d.RDB$DEPENDENT_NAME
              JOIN RDB$RELATION_CONSTRAINTS rc ON rc.RDB$CONSTRAINT_NAME = cc.RDB$CONSTRAINT_NAME
             WHERE d.RDB$DEPENDENT_TYPE = 2 AND d.RDB$DEPENDED_ON_TYPE IN (5, 15, 18)""";

    // Dependente tipo 5 é procedure, 15 function e 19 corpo de package; o que ela lê é view (1).
    private static final String VIEW_READERS = """
            SELECT DISTINCT d.RDB$DEPENDENT_NAME, d.RDB$DEPENDENT_TYPE, d.RDB$DEPENDED_ON_NAME
              FROM RDB$DEPENDENCIES d
             WHERE d.RDB$DEPENDENT_TYPE IN (5, 15, 19) AND d.RDB$DEPENDED_ON_TYPE = 1""";

    private final Connection connection;

    RoutineCatalog(Connection connection) {
        this.connection = connection;
    }

    /**
     * Um parâmetro de procedure ou function. {@code domain} é o nome do domínio, se o tipo veio de um; {@code typeOf}
     * é o {@code TYPE OF} (de domínio ou, com {@code typeRelation}, de coluna); {@code type} é o tipo físico, que só
     * vale quando não há domínio.
     */
    record Parameter(String name, String domain, boolean typeOf, String typeRelation, String typeColumn,
            FieldType type, boolean notNull, String defaultSource) {

        String typeSql(ExtractionOptions options, String defaultCharacterSet) {
            if (typeRelation != null) {
                return "TYPE OF COLUMN " + options.quote(typeRelation) + "." + options.quote(typeColumn);
            }
            if (domain != null) {
                return (typeOf ? "TYPE OF " : "") + options.quote(domain);
            }
            return type.toSql(defaultCharacterSet, options.alwaysCharacterSet());
        }

        String toSql(ExtractionOptions options, String defaultCharacterSet) {
            return options.quote(name) + " " + typeSql(options, defaultCharacterSet) + (notNull ? " NOT NULL" : "")
                    + (defaultSource == null ? "" : " " + defaultSource);
        }
    }

    /** Uma procedure. {@code engine} não nulo é uma rotina externa (UDR), que sai inteira no Cabeçalho. */
    record Procedure(String name, List<Parameter> inputs, List<Parameter> outputs, String source, boolean selectable,
            String engine, String entryPoint, Boolean definer) {

        boolean external() {
            return engine != null;
        }

        /** A assinatura, até antes do {@code AS}. */
        private String signature(String keyword, ExtractionOptions options, String defaultCharacterSet) {
            StringBuilder sql = new StringBuilder(keyword).append(' ').append(options.quote(name));
            appendParameters(sql, " (", inputs, options, defaultCharacterSet);
            if (!outputs.isEmpty()) {
                sql.append('\n');
                appendParameters(sql, "RETURNS (", outputs, options, defaultCharacterSet);
            }
            return sql.append(securityClause(definer)).toString();
        }

        /** O Cabeçalho vazio: o corpo só sai ({@code EXIT}) ou entrega uma linha ({@code SUSPEND}) sem fazer nada. */
        String emptyHeader(ExtractionOptions options, String defaultCharacterSet) {
            if (external()) {
                return signature("CREATE PROCEDURE", options, defaultCharacterSet) + externalBody(engine, entryPoint,
                        source);
            }
            return signature("CREATE PROCEDURE", options, defaultCharacterSet) + "\nAS\nBEGIN\n  "
                    + (selectable ? "SUSPEND" : "EXIT") + ";\nEND";
        }

        String body(ExtractionOptions options, String defaultCharacterSet) {
            return signature(options.createOrAlter() ? "CREATE OR ALTER PROCEDURE" : "ALTER PROCEDURE", options,
                    defaultCharacterSet) + asSource(source);
        }
    }

    /** Uma function de schema (não legada). {@code returns} é o tipo de retorno. */
    record Function(String name, List<Parameter> inputs, Parameter returns, String source, boolean deterministic,
            String engine, String entryPoint, Boolean definer) {

        boolean external() {
            return engine != null;
        }

        private String signature(String keyword, ExtractionOptions options, String defaultCharacterSet) {
            StringBuilder sql = new StringBuilder(keyword).append(' ').append(options.quote(name));
            appendParameters(sql, " (", inputs, options, defaultCharacterSet);
            sql.append("\nRETURNS ").append(returns.typeSql(options, defaultCharacterSet))
                    .append(returns.notNull() ? " NOT NULL" : "");
            if (deterministic) {
                sql.append("\nDETERMINISTIC");
            }
            return sql.append(securityClause(definer)).toString();
        }

        String emptyHeader(ExtractionOptions options, String defaultCharacterSet) {
            if (external()) {
                return signature("CREATE FUNCTION", options, defaultCharacterSet) + externalBody(engine, entryPoint,
                        source);
            }
            return signature("CREATE FUNCTION", options, defaultCharacterSet) + "\nAS\nBEGIN\n  RETURN NULL;\nEND";
        }

        String body(ExtractionOptions options, String defaultCharacterSet) {
            return signature(options.createOrAlter() ? "CREATE OR ALTER FUNCTION" : "ALTER FUNCTION", options,
                    defaultCharacterSet) + asSource(source);
        }
    }

    /** Um package: o header (assinaturas) e o body, que pode faltar. As fontes já trazem o {@code BEGIN ... END}. */
    record Package(String name, String headerSource, String bodySource, Boolean definer) {

        String header(ExtractionOptions options) {
            return "CREATE PACKAGE " + options.quote(name) + securityClause(definer) + asSource(headerSource);
        }

        String body(ExtractionOptions options) {
            return "CREATE PACKAGE BODY " + options.quote(name) + asSource(bodySource);
        }
    }

    /** As rotinas da seleção, cada lista em ordem alfabética. */
    record Routines(List<Package> packages, List<Function> functions, List<Procedure> procedures) {

        int size() {
            return packages.size() + functions.size() + procedures.size();
        }
    }

    /** Quem, numa tabela, depende da rotina {@code routine} (já no formato {@code procedure X}). */
    record TableUser(String table, String user, String routine) {
    }

    List<String> listProcedureNames() throws SQLException {
        return names(PROCEDURES);
    }

    List<String> listFunctionNames() throws SQLException {
        return names(FUNCTIONS);
    }

    List<String> listPackageNames() throws SQLException {
        return names(PACKAGES);
    }

    Routines readRoutines(Selection selection, ExtractionProgress progress) throws SQLException {
        Map<String, List<Parameter>> inputs = new LinkedHashMap<>();
        Map<String, List<Parameter>> outputs = new LinkedHashMap<>();
        readParameters(PROCEDURE_PARAMETERS, "DIRECTION", progress, (owner, direction, parameter) ->
                (direction == 0 ? inputs : outputs).computeIfAbsent(owner, k -> new ArrayList<>()).add(parameter));
        Map<String, Map<Integer, Parameter>> arguments = new LinkedHashMap<>();
        readParameters(FUNCTION_ARGUMENTS, "ARG_POSITION", progress, (owner, position, parameter) ->
                arguments.computeIfAbsent(owner, k -> new TreeMap<>()).put(position, parameter));

        List<Procedure> procedures = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(PROCEDURES)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                if (selection.includesProcedure(name)) {
                    procedures.add(new Procedure(name, inputs.getOrDefault(name, List.of()),
                            outputs.getOrDefault(name, List.of()), rs.getString(2), rs.getInt(3) == 1,
                            Rdb.trimmed(rs.getString(4)), Rdb.trimmed(rs.getString(5)), security(rs, 6)));
                }
            }
        }
        List<Function> functions = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(FUNCTIONS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                if (selection.includesFunction(name)) {
                    // A posição do retorno vem de RDB$RETURN_ARGUMENT; as demais são os parâmetros de entrada.
                    Map<Integer, Parameter> all = new TreeMap<>(arguments.getOrDefault(name, Map.of()));
                    Parameter returns = all.remove(rs.getInt(3));
                    List<Parameter> in = new ArrayList<>(all.values());
                    functions.add(new Function(name, in, returns, rs.getString(2), rs.getInt(4) == 1,
                            Rdb.trimmed(rs.getString(5)), Rdb.trimmed(rs.getString(6)), security(rs, 7)));
                }
            }
        }
        List<Package> packages = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(PACKAGES)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                if (selection.includesPackage(name)) {
                    packages.add(new Package(name, rs.getString(2), rs.getString(3), security(rs, 4)));
                }
            }
        }
        return new Routines(packages, functions, procedures);
    }

    /**
     * Colunas calculadas, índices de expressão e checks de tabela que chamam rotina. A tabela é criada antes dos
     * Cabeçalhos vazios, então nada garante que a rotina já exista: o script só roda se ela estiver no banco antes.
     */
    List<TableUser> readTableUsers() throws SQLException {
        List<TableUser> users = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(TABLE_USERS)) {
            while (rs.next()) {
                users.add(new TableUser(rs.getString(1).strip(), rs.getString(2).strip(),
                        routineName(rs.getInt(4), rs.getString(3).strip(), Rdb.trimmed(rs.getString(5)))));
            }
        }
        return users;
    }

    /**
     * Para cada rotina ({@code procedure X}, {@code function X}, {@code package X}), as views que o corpo dela lê. Quem
     * escreve o script usa para pôr a view pronta antes do corpo.
     */
    Map<String, Set<String>> readViewReaders() throws SQLException {
        Map<String, Set<String>> readers = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(VIEW_READERS)) {
            while (rs.next()) {
                String key = switch (rs.getInt(2)) {
                    case 5 -> "procedure ";
                    case 15 -> "function ";
                    default -> "package ";
                } + rs.getString(1).strip();
                readers.computeIfAbsent(key, k -> new TreeSet<>()).add(rs.getString(3).strip());
            }
        }
        return readers;
    }

    /** O nome da rotina como as mensagens e a seleção a chamam: {@code procedure X}, {@code function X} ou {@code package X}. */
    static String routineName(int dependencyType, String name, String packageName) {
        if (packageName != null) {
            return "package " + packageName;
        }
        return switch (dependencyType) {
            case 5 -> "procedure " + name;
            case 15 -> "function " + name;
            default -> "package " + name;
        };
    }

    private interface ParameterSink {
        void accept(String owner, int key, Parameter parameter);
    }

    private void readParameters(String sql, String keyColumn, ExtractionProgress progress, ParameterSink sink)
            throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                progress.checkCancelled();
                String fieldSource = rs.getString("RDB$FIELD_SOURCE").strip();
                boolean anonymous = fieldSource.startsWith("RDB$");
                boolean typeOf = rs.getInt("MECHANISM") == 1;
                String relation = Rdb.trimmed(rs.getString("TYPE_RELATION"));
                String column = Rdb.trimmed(rs.getString("TYPE_COLUMN"));
                FieldType type = new FieldType(rs.getInt("RDB$FIELD_TYPE"), rs.getInt("RDB$FIELD_SUB_TYPE"),
                        rs.getInt("RDB$FIELD_LENGTH"), Rdb.nullableInt(rs, "RDB$CHARACTER_LENGTH"),
                        Rdb.nullableInt(rs, "RDB$FIELD_PRECISION"), rs.getInt("RDB$FIELD_SCALE"),
                        Rdb.nullableInt(rs, "RDB$SEGMENT_LENGTH"), Rdb.trimmed(rs.getString("RDB$CHARACTER_SET_NAME")),
                        Rdb.trimmed(rs.getString("RDB$COLLATION_NAME")), null);
                String name = Rdb.trimmed(rs.getString("NAME"));
                Parameter parameter = new Parameter(name, anonymous ? null : fieldSource, typeOf,
                        typeOf ? relation : null, typeOf ? column : null, type, rs.getInt("RDB$NULL_FLAG") == 1,
                        Rdb.source(rs.getString("RDB$DEFAULT_SOURCE")));
                sink.accept(rs.getString("OWNER").strip(), rs.getInt(keyColumn), parameter);
            }
        }
    }

    private List<String> names(String sql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1).strip());
            }
        }
        return names;
    }

    /** {@code RDB$SQL_SECURITY}: {@code null} é o padrão do banco, que o script não precisa repetir. */
    private static Boolean security(ResultSet rs, int column) throws SQLException {
        boolean definer = rs.getBoolean(column);
        return rs.wasNull() ? null : definer;
    }

    private static String securityClause(Boolean definer) {
        return definer == null ? "" : "\nSQL SECURITY " + (definer ? "DEFINER" : "INVOKER");
    }

    private static void appendParameters(StringBuilder sql, String opening, List<Parameter> parameters,
            ExtractionOptions options, String defaultCharacterSet) {
        if (parameters.isEmpty()) {
            return;
        }
        sql.append(opening).append("\n").append(parameters.stream()
                .map(parameter -> "    " + parameter.toSql(options, defaultCharacterSet))
                .collect(Collectors.joining(",\n"))).append(')');
    }

    /** O que vem depois da assinatura: a Fonte do banco, sem tocar nela (nula, só em catálogo estranho, vira corpo vazio). */
    private static String asSource(String source) {
        if (source == null) {
            return "\nAS\nBEGIN\nEND";
        }
        return "\nAS" + (source.startsWith("\n") || source.startsWith("\r") ? "" : "\n") + source;
    }

    private static String externalBody(String engine, String entryPoint, String source) {
        String body = "\nEXTERNAL NAME '" + entryPoint.replace("'", "''") + "' ENGINE " + engine;
        return source == null || source.isBlank() ? body : body + "\nAS '" + source.replace("'", "''") + "'";
    }
}
