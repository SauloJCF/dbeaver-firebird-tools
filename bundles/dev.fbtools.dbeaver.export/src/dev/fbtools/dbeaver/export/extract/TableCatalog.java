package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tabelas, constraints e índices lidos de {@code RDB$*} (ADR 0007). Cada leitura devolve só o que a {@link Selection}
 * pede; o SQL de cada peça fica nos próprios registros, para a extração ordenar as Fases.
 */
final class TableCatalog {

    // Nomes que o servidor dá a constraints sem nome (INTEG_12): ao recriar, o servidor dá outro, então saem sem nome.
    private static final Pattern GENERATED_NAME = Pattern.compile("INTEG_\\d+");

    private static final String COLUMNS = """
            SELECT rf.RDB$RELATION_NAME, rf.RDB$FIELD_NAME, rf.RDB$FIELD_SOURCE, rf.RDB$NULL_FLAG,
                   rf.RDB$DEFAULT_SOURCE AS RF_DEFAULT_SOURCE, rf.RDB$COLLATION_ID AS RF_COLLATION_ID,
                   rf.RDB$IDENTITY_TYPE, rf.RDB$GENERATOR_NAME,
                   f.RDB$FIELD_TYPE, f.RDB$FIELD_SUB_TYPE, f.RDB$FIELD_LENGTH, f.RDB$CHARACTER_LENGTH,
                   f.RDB$FIELD_PRECISION, f.RDB$FIELD_SCALE, f.RDB$SEGMENT_LENGTH, f.RDB$COMPUTED_SOURCE,
                   f.RDB$DEFAULT_SOURCE AS F_DEFAULT_SOURCE, f.RDB$COLLATION_ID AS F_COLLATION_ID,
                   cs.RDB$CHARACTER_SET_NAME, co.RDB$COLLATION_NAME, rco.RDB$COLLATION_NAME AS RF_COLLATION_NAME,
                   g.RDB$INITIAL_VALUE, g.RDB$GENERATOR_INCREMENT
              FROM RDB$RELATION_FIELDS rf
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = rf.RDB$RELATION_NAME
              JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = rf.RDB$FIELD_SOURCE
              LEFT JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
              LEFT JOIN RDB$COLLATIONS co ON co.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
                                         AND co.RDB$COLLATION_ID = f.RDB$COLLATION_ID
              LEFT JOIN RDB$COLLATIONS rco ON rco.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
                                          AND rco.RDB$COLLATION_ID = rf.RDB$COLLATION_ID
              LEFT JOIN RDB$GENERATORS g ON g.RDB$GENERATOR_NAME = rf.RDB$GENERATOR_NAME
             WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0 AND r.RDB$VIEW_BLR IS NULL
             ORDER BY rf.RDB$RELATION_NAME, rf.RDB$FIELD_POSITION""";

    private static final String TABLES = """
            SELECT RDB$RELATION_NAME, RDB$RELATION_TYPE, RDB$EXTERNAL_FILE FROM RDB$RELATIONS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$VIEW_BLR IS NULL ORDER BY RDB$RELATION_NAME""";

    private static final String CONSTRAINTS = """
            SELECT rc.RDB$RELATION_NAME, rc.RDB$CONSTRAINT_NAME, rc.RDB$CONSTRAINT_TYPE, rc.RDB$INDEX_NAME,
                   i.RDB$INDEX_TYPE, refc.RDB$UPDATE_RULE, refc.RDB$DELETE_RULE, uq.RDB$RELATION_NAME AS REF_TABLE,
                   uq.RDB$INDEX_NAME AS REF_INDEX
              FROM RDB$RELATION_CONSTRAINTS rc
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = rc.RDB$RELATION_NAME
              LEFT JOIN RDB$INDICES i ON i.RDB$INDEX_NAME = rc.RDB$INDEX_NAME
              LEFT JOIN RDB$REF_CONSTRAINTS refc ON refc.RDB$CONSTRAINT_NAME = rc.RDB$CONSTRAINT_NAME
              LEFT JOIN RDB$RELATION_CONSTRAINTS uq ON uq.RDB$CONSTRAINT_NAME = refc.RDB$CONST_NAME_UQ
             WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0 AND r.RDB$VIEW_BLR IS NULL
               AND rc.RDB$CONSTRAINT_TYPE <> 'NOT NULL'
             ORDER BY rc.RDB$RELATION_NAME, rc.RDB$CONSTRAINT_NAME""";

    // O check mora no trigger de BEFORE INSERT (tipo 1) que o servidor cria para ele; o de UPDATE repete o texto.
    private static final String CHECKS = """
            SELECT cc.RDB$CONSTRAINT_NAME, t.RDB$TRIGGER_SOURCE FROM RDB$CHECK_CONSTRAINTS cc
              JOIN RDB$TRIGGERS t ON t.RDB$TRIGGER_NAME = cc.RDB$TRIGGER_NAME AND t.RDB$TRIGGER_TYPE = 1""";

    private static final String INDEX_SEGMENTS = """
            SELECT s.RDB$INDEX_NAME, s.RDB$FIELD_NAME FROM RDB$INDEX_SEGMENTS s
              JOIN RDB$INDICES i ON i.RDB$INDEX_NAME = s.RDB$INDEX_NAME
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = i.RDB$RELATION_NAME
             WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0
             ORDER BY s.RDB$INDEX_NAME, s.RDB$FIELD_POSITION""";

    // Os índices das constraints saem com a constraint (USING INDEX quando o nome foi escolhido).
    private static final String INDICES = """
            SELECT i.RDB$INDEX_NAME, i.RDB$RELATION_NAME, i.RDB$UNIQUE_FLAG, i.RDB$INDEX_TYPE, i.RDB$INDEX_INACTIVE,
                   i.RDB$EXPRESSION_SOURCE, i.RDB$CONDITION_SOURCE
              FROM RDB$INDICES i
              JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = i.RDB$RELATION_NAME
             WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0 AND r.RDB$VIEW_BLR IS NULL
               AND NOT EXISTS (SELECT 1 FROM RDB$RELATION_CONSTRAINTS rc WHERE rc.RDB$INDEX_NAME = i.RDB$INDEX_NAME)
             ORDER BY i.RDB$INDEX_NAME""";

    private final Connection connection;

    TableCatalog(Connection connection) {
        this.connection = connection;
    }

    /** Identity de uma coluna; {@code current} é o valor do generator, e a próxima linha recebe {@code current + increment}. */
    record Identity(boolean always, long start, long increment, String generator, long current) {

        Identity withCurrent(long value) {
            return new Identity(always, start, increment, generator, value);
        }

        String clause() {
            StringBuilder sql = new StringBuilder(" GENERATED ").append(always ? "ALWAYS" : "BY DEFAULT")
                    .append(" AS IDENTITY");
            List<String> options = new ArrayList<>();
            if (start != 1) {
                options.add("START WITH " + start);
            }
            if (increment != 1) {
                options.add("INCREMENT BY " + increment);
            }
            return options.isEmpty() ? sql.toString() : sql + " (" + String.join(" ", options) + ")";
        }

        /** O valor com que a identity precisa recomeçar para a próxima linha receber o mesmo número; {@code null} se for o início. */
        Long restartWith() {
            long next = current + increment;
            return next == start ? null : next;
        }
    }

    /**
     * Uma coluna. Exatamente um de {@code domain}, {@code computed} e {@code type} descreve o tipo: domínio do usuário,
     * expressão {@code COMPUTED BY} (já entre parênteses) ou tipo declarado na coluna.
     */
    record Column(String name, String domain, String computed, FieldType type, String defaultSource, boolean notNull,
            String collation, Identity identity) {

        Column withIdentity(Identity changed) {
            return new Column(name, domain, computed, type, defaultSource, notNull, collation, changed);
        }

        String definition(ExtractionOptions options, String defaultCharacterSet) {
            StringBuilder sql = new StringBuilder(options.quote(name)).append(' ');
            if (computed != null) {
                return sql.append("COMPUTED BY ").append(computed).toString();
            }
            if (domain != null) {
                sql.append(options.quote(domain));
            } else {
                sql.append(type.toSql(defaultCharacterSet, options.alwaysCharacterSet()));
            }
            if (identity != null) {
                // A identity já é NOT NULL, e a sintaxe não aceita os dois.
                sql.append(identity.clause());
            }
            if (defaultSource != null) {
                sql.append(' ').append(defaultSource);
            }
            if (notNull && identity == null) {
                sql.append(" NOT NULL");
            }
            if (collation != null) {
                sql.append(" COLLATE ").append(options.quote(collation));
            }
            return sql.toString();
        }
    }

    /** Uma constraint de tabela. {@code kind}: {@code PRIMARY KEY}, {@code UNIQUE}, {@code FOREIGN KEY} ou {@code CHECK}. */
    record Constraint(String table, String name, String kind, List<String> columns, String refTable,
            List<String> refColumns, String updateRule, String deleteRule, String checkSource, String indexName,
            boolean descendingIndex) {

        boolean isKey() {
            return kind.equals("PRIMARY KEY") || kind.equals("UNIQUE");
        }

        String toSql(ExtractionOptions options) {
            StringBuilder sql = new StringBuilder("ALTER TABLE ").append(options.quote(table)).append(" ADD ");
            if (!GENERATED_NAME.matcher(name).matches()) {
                sql.append("CONSTRAINT ").append(options.quote(name)).append(' ');
            }
            switch (kind) {
                case "CHECK" -> sql.append(checkSource);
                case "FOREIGN KEY" -> {
                    sql.append("FOREIGN KEY (").append(names(columns, options)).append(") REFERENCES ")
                            .append(options.quote(refTable)).append(" (").append(names(refColumns, options))
                            .append(')');
                    appendRule(sql, "DELETE", deleteRule);
                    appendRule(sql, "UPDATE", updateRule);
                }
                default -> sql.append(kind).append(" (").append(names(columns, options)).append(')');
            }
            // O índice da constraint leva o nome da constraint, salvo se o autor escolheu outro (USING INDEX).
            boolean namedIndex = indexName != null && !indexName.startsWith("RDB$") && !indexName.equals(name);
            if (namedIndex || descendingIndex) {
                sql.append(" USING ").append(descendingIndex ? "DESC " : "").append("INDEX ")
                        .append(options.quote(namedIndex ? indexName : name));
            }
            return sql.append(';').toString();
        }

        private static void appendRule(StringBuilder sql, String event, String rule) {
            // RESTRICT é como o servidor guarda a regra padrão (NO ACTION).
            if (rule != null && !rule.equals("RESTRICT") && !rule.equals("NO ACTION")) {
                sql.append(" ON ").append(event).append(' ').append(rule);
            }
        }
    }

    /** Uma tabela com suas colunas e constraints. {@code type}: {@code RDB$RELATION_TYPE} (4 e 5 são Global Temporary Tables). */
    record Table(String name, int type, String externalFile, List<Column> columns, List<Constraint> constraints) {

        String toSql(ExtractionOptions options, String defaultCharacterSet) {
            StringBuilder sql = new StringBuilder("CREATE ");
            if (type == 4 || type == 5) {
                sql.append("GLOBAL TEMPORARY ");
            }
            sql.append("TABLE ").append(options.quote(name));
            if (externalFile != null) {
                sql.append(" EXTERNAL FILE '").append(externalFile.replace("'", "''")).append('\'');
            }
            sql.append(" (\n");
            List<String> lines = new ArrayList<>();
            for (Column column : columns) {
                if (column.computed() == null || !options.computedBySeparate()) {
                    lines.add("    " + column.definition(options, defaultCharacterSet));
                }
            }
            sql.append(String.join(",\n", lines)).append("\n)");
            if (type == 4) {
                sql.append(" ON COMMIT PRESERVE ROWS");
            } else if (type == 5) {
                sql.append(" ON COMMIT DELETE ROWS");
            }
            return sql.append(';').toString();
        }

        List<Column> computedColumns() {
            return columns.stream().filter(column -> column.computed() != null).toList();
        }

        List<Column> identityColumns() {
            return columns.stream().filter(column -> column.identity() != null).toList();
        }
    }

    /** Um índice do usuário (não o de uma constraint). */
    record Index(String name, String table, boolean unique, boolean descending, boolean inactive,
            List<String> columns, String expression, String condition) {

        String toSql(ExtractionOptions options) {
            StringBuilder sql = new StringBuilder("CREATE ");
            if (unique) {
                sql.append("UNIQUE ");
            }
            if (descending) {
                sql.append("DESCENDING ");
            }
            sql.append("INDEX ").append(options.quote(name)).append(" ON ").append(options.quote(table));
            if (expression != null) {
                sql.append(" COMPUTED BY ").append(expression);
            } else {
                sql.append(" (").append(names(columns, options)).append(')');
            }
            if (condition != null) {
                sql.append(' ').append(condition);
            }
            return sql.append(';').toString();
        }

        /** O índice inativo só se desativa depois de criado. */
        String inactiveSql(ExtractionOptions options) {
            return "ALTER INDEX " + options.quote(name) + " INACTIVE;";
        }
    }

    private static String names(List<String> columns, ExtractionOptions options) {
        return String.join(", ", columns.stream().map(options::quote).toList());
    }

    /** As tabelas do usuário na seleção, com colunas e constraints, em ordem alfabética (as FKs vão para outra Fase). */
    List<Table> readTables(Selection selection, ExtractionProgress progress) throws SQLException {
        Map<String, String> dimensions = Rdb.dimensions(connection);
        Map<String, List<Column>> columns = readColumns(dimensions, progress);
        Map<String, List<Constraint>> constraints = readConstraints(progress);
        List<Table> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(TABLES)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                if (selection.includesTable(name)) {
                    tables.add(new Table(name, rs.getInt(2), Rdb.source(rs.getString(3)),
                            columns.getOrDefault(name, List.of()), constraints.getOrDefault(name, List.of())));
                }
            }
        }
        return tables;
    }

    /** Só os nomes das tabelas do usuário, sem ler colunas nem constraints (para a aba Objects). */
    List<String> listTableNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(TABLES)) {
            while (rs.next()) {
                names.add(rs.getString(1).strip());
            }
        }
        return names;
    }

    /** Os índices do usuário na seleção, com as colunas já na ordem dos segmentos. */
    List<Index> readIndexes(Selection selection, ExtractionProgress progress) throws SQLException {
        Map<String, List<String>> segments = readSegments();
        List<Index> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(INDICES)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString("RDB$INDEX_NAME").strip();
                if (selection.includesIndex(name)) {
                    indexes.add(new Index(name, rs.getString("RDB$RELATION_NAME").strip(),
                            rs.getInt("RDB$UNIQUE_FLAG") == 1, rs.getInt("RDB$INDEX_TYPE") == 1,
                            rs.getInt("RDB$INDEX_INACTIVE") == 1, segments.getOrDefault(name, List.of()),
                            Rdb.source(rs.getString("RDB$EXPRESSION_SOURCE")),
                            Rdb.source(rs.getString("RDB$CONDITION_SOURCE"))));
                }
            }
        }
        return indexes;
    }

    private Map<String, List<Column>> readColumns(Map<String, String> dimensions, ExtractionProgress progress)
            throws SQLException {
        Map<String, List<Column>> columns = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(COLUMNS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String source = rs.getString("RDB$FIELD_SOURCE").strip();
                columns.computeIfAbsent(rs.getString("RDB$RELATION_NAME").strip(), table -> new ArrayList<>())
                        .add(column(rs, source, dimensions.get(source)));
            }
        }
        readIdentityValues(columns);
        return columns;
    }

    private Column column(ResultSet rs, String source, String dimensions) throws SQLException {
        String name = rs.getString("RDB$FIELD_NAME").strip();
        boolean notNull = rs.getInt("RDB$NULL_FLAG") == 1;
        String computed = Rdb.source(rs.getString("RDB$COMPUTED_SOURCE"));
        if (computed != null) {
            return new Column(name, null, computed(computed), null, null, false, null, null);
        }
        Identity identity = identity(rs);
        if (!source.startsWith("RDB$")) {
            // Domínio do usuário: o padrão da coluna e o COLLATE só entram se a coluna os mudou.
            Integer columnCollation = Rdb.nullableInt(rs, "RF_COLLATION_ID");
            Integer domainCollation = Rdb.nullableInt(rs, "F_COLLATION_ID");
            boolean collates = columnCollation != null && !columnCollation.equals(domainCollation == null ? 0 : domainCollation);
            return new Column(name, source, null, null, Rdb.source(rs.getString("RF_DEFAULT_SOURCE")), notNull,
                    collates ? Rdb.trimmed(rs.getString("RF_COLLATION_NAME")) : null, identity);
        }
        String defaultSource = Rdb.source(rs.getString("RF_DEFAULT_SOURCE"));
        if (defaultSource == null) {
            defaultSource = Rdb.source(rs.getString("F_DEFAULT_SOURCE"));
        }
        FieldType type = new FieldType(rs.getInt("RDB$FIELD_TYPE"), rs.getInt("RDB$FIELD_SUB_TYPE"),
                rs.getInt("RDB$FIELD_LENGTH"), Rdb.nullableInt(rs, "RDB$CHARACTER_LENGTH"),
                Rdb.nullableInt(rs, "RDB$FIELD_PRECISION"), rs.getInt("RDB$FIELD_SCALE"),
                Rdb.nullableInt(rs, "RDB$SEGMENT_LENGTH"), Rdb.trimmed(rs.getString("RDB$CHARACTER_SET_NAME")),
                Rdb.trimmed(rs.getString("RDB$COLLATION_NAME")), dimensions);
        return new Column(name, null, null, type, defaultSource, notNull, null, identity);
    }

    /** O servidor guarda a expressão como {@code (expr)}; versões antigas guardam {@code COMPUTED BY (expr)}. */
    private static String computed(String source) {
        String text = source.regionMatches(true, 0, "COMPUTED BY", 0, 11) ? source.substring(11).strip() : source;
        return text.startsWith("(") ? text : "(" + text + ")";
    }

    /** O valor atual do generator fica para depois: outro statement fecharia o ResultSet das colunas. */
    private static Identity identity(ResultSet rs) throws SQLException {
        Integer kind = Rdb.nullableInt(rs, "RDB$IDENTITY_TYPE");
        if (kind == null) {
            return null;
        }
        // Bancos migrados de versões antigas podem ter início e incremento nulos: valem 1.
        Long start = Rdb.nullableLong(rs, "RDB$INITIAL_VALUE");
        Long increment = Rdb.nullableLong(rs, "RDB$GENERATOR_INCREMENT");
        return new Identity(kind == 0, start == null ? 1 : start, increment == null ? 1 : increment,
                rs.getString("RDB$GENERATOR_NAME").strip(), 0);
    }

    private void readIdentityValues(Map<String, List<Column>> columns) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (List<Column> table : columns.values()) {
                for (int i = 0; i < table.size(); i++) {
                    Column column = table.get(i);
                    if (column.identity() == null) {
                        continue;
                    }
                    String generator = column.identity().generator();
                    try (ResultSet value = statement.executeQuery(
                            "SELECT GEN_ID(\"" + generator.replace("\"", "\"\"") + "\", 0) FROM RDB$DATABASE")) {
                        value.next();
                        table.set(i, column.withIdentity(column.identity().withCurrent(value.getLong(1))));
                    }
                }
            }
        }
    }

    private Map<String, List<Constraint>> readConstraints(ExtractionProgress progress) throws SQLException {
        Map<String, List<String>> segments = readSegments();
        Map<String, String> checks = readChecks();
        Map<String, List<Constraint>> constraints = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(CONSTRAINTS)) {
            while (rs.next()) {
                String table = rs.getString("RDB$RELATION_NAME").strip();
                String name = rs.getString("RDB$CONSTRAINT_NAME").strip();
                String kind = rs.getString("RDB$CONSTRAINT_TYPE").strip();
                if (kind.equals("CHECK") && checks.get(name) == null) {
                    progress.warn("A constraint CHECK " + name + " da tabela " + table
                            + " não tem o trigger no catálogo e ficou fora do script.");
                    continue;
                }
                String index = Rdb.trimmed(rs.getString("RDB$INDEX_NAME"));
                List<String> refColumns = List.of();
                if (kind.equals("FOREIGN KEY")) {
                    refColumns = segments.getOrDefault(Rdb.trimmed(rs.getString("REF_INDEX")), List.of());
                }
                constraints.computeIfAbsent(table, t -> new ArrayList<>()).add(new Constraint(table, name, kind,
                        index == null ? List.of() : segments.getOrDefault(index, List.of()),
                        Rdb.trimmed(rs.getString("REF_TABLE")), refColumns, Rdb.trimmed(rs.getString("RDB$UPDATE_RULE")),
                        Rdb.trimmed(rs.getString("RDB$DELETE_RULE")), kind.equals("CHECK") ? check(checks.get(name)) : null,
                        index, rs.getInt("RDB$INDEX_TYPE") == 1));
            }
        }
        return constraints;
    }

    private static String check(String source) {
        String text = source == null ? "" : source.strip();
        return text.regionMatches(true, 0, "CHECK", 0, 5) ? text : "CHECK (" + text + ")";
    }

    private Map<String, String> readChecks() throws SQLException {
        Map<String, String> checks = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(CHECKS)) {
            while (rs.next()) {
                checks.put(rs.getString(1).strip(), rs.getString(2));
            }
        }
        return checks;
    }

    private Map<String, List<String>> readSegments() throws SQLException {
        Map<String, List<String>> segments = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(INDEX_SEGMENTS)) {
            while (rs.next()) {
                segments.computeIfAbsent(rs.getString(1).strip(), index -> new ArrayList<>())
                        .add(rs.getString(2).strip());
            }
        }
        return segments;
    }
}
