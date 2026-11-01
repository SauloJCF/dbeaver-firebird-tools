package dev.fbtools.dbeaver.export.extract;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Gera o Script de metadata lendo {@code RDB$*} direto (ADR 0007). Nesta fatia: domínios, generators, tabelas, índices
 * e constraints, na ordem das Fases (domínios, generators com {@code SET GENERATOR}, tabelas com chaves primárias e
 * únicas, depois colunas calculadas separadas, índices, checks e FKs). A conexão deve usar o charset padrão do banco,
 * para que colunas {@code NONE} cheguem byte a byte (ADR 0009).
 */
public final class MetadataExtractor {

    private static final String DOMAINS = """
            SELECT f.RDB$FIELD_NAME, f.RDB$FIELD_TYPE, f.RDB$FIELD_SUB_TYPE, f.RDB$FIELD_LENGTH,
                   f.RDB$CHARACTER_LENGTH, f.RDB$FIELD_PRECISION, f.RDB$FIELD_SCALE, f.RDB$SEGMENT_LENGTH,
                   f.RDB$DEFAULT_SOURCE, f.RDB$VALIDATION_SOURCE, f.RDB$NULL_FLAG, f.RDB$DESCRIPTION,
                   cs.RDB$CHARACTER_SET_NAME, co.RDB$COLLATION_NAME
              FROM RDB$FIELDS f
              LEFT JOIN RDB$CHARACTER_SETS cs ON cs.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
              LEFT JOIN RDB$COLLATIONS co ON co.RDB$CHARACTER_SET_ID = f.RDB$CHARACTER_SET_ID
                                         AND co.RDB$COLLATION_ID = f.RDB$COLLATION_ID
             WHERE COALESCE(f.RDB$SYSTEM_FLAG, 0) = 0 AND f.RDB$FIELD_NAME NOT STARTING WITH 'RDB$'
             ORDER BY f.RDB$FIELD_NAME""";

    // Generators de identity (RDB$SYSTEM_FLAG = 6) e de sistema pertencem à tabela ou ao servidor, não ao script.
    private static final String GENERATORS = """
            SELECT RDB$GENERATOR_NAME, RDB$DESCRIPTION FROM RDB$GENERATORS
             WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY RDB$GENERATOR_NAME""";

    private final Connection connection;

    public MetadataExtractor(Connection connection) {
        this.connection = connection;
    }

    /** Nomes dos domínios do usuário, para a aba Objects. */
    public List<String> listDomains() throws SQLException {
        List<String> names = new ArrayList<>();
        for (Domain domain : readDomains(Selection.all(), ExtractionProgress.NONE)) {
            names.add(domain.name());
        }
        return names;
    }

    /** Nomes dos generators do usuário, para a aba Objects. */
    public List<String> listGenerators() throws SQLException {
        return new ArrayList<>(readGenerators(Selection.all()).keySet());
    }

    /** Nomes das tabelas do usuário, para a aba Objects. */
    public List<String> listTables() throws SQLException {
        return new TableCatalog(connection).listTableNames();
    }

    /** Nomes dos índices do usuário (sem os das constraints), para a aba Objects. */
    public List<String> listIndexes() throws SQLException {
        return new TableCatalog(connection).readIndexes(Selection.all(), ExtractionProgress.NONE).stream()
                .map(TableCatalog.Index::name).toList();
    }

    /** Como {@link #extract(Selection, ExtractionOptions, Writer, String, ExtractionProgress)} com as opções padrão. */
    public void extract(Selection selection, Writer out, String setNames, ExtractionProgress progress)
            throws SQLException, IOException {
        extract(selection, ExtractionOptions.DEFAULT, out, setNames, progress);
    }

    /**
     * Escreve o script. Não fecha o {@code out}. {@code setNames} é o charset do {@code SET NAMES} (o do arquivo);
     * {@code null} usa o padrão do banco.
     *
     * @throws ExtractionCancelledException se {@code progress} pediu o cancelamento
     */
    public void extract(Selection selection, ExtractionOptions options, Writer out, String setNames,
            ExtractionProgress progress) throws SQLException, IOException {
        DatabaseInfo info = DatabaseInfo.read(connection);
        TableCatalog catalog = new TableCatalog(connection);
        List<Domain> domains = readDomains(selection, progress);
        Map<String, String> generators = readGenerators(selection);
        List<TableCatalog.Table> tables = catalog.readTables(selection, progress);
        List<TableCatalog.Index> indexes = catalog.readIndexes(selection, progress);
        warnIfConnectionCharsetDiffers(info, progress);
        warnAboutNoneColumns(info, domains, tables, progress);
        warnAboutMissingDependencies(domains, tables, progress);
        progress.begin(domains.size() + generators.size() + tables.size() + indexes.size());

        out.write("SET SQL DIALECT 3;\n");
        out.write("SET NAMES " + (setNames != null ? setNames : info.defaultCharacterSet()) + ";\n");
        if (options.createDatabase()) {
            out.write("\n" + createDatabase(info) + "\n");
        }

        if (!domains.isEmpty()) {
            out.write("\n/* Domains */\n\n");
        }
        for (Domain domain : domains) {
            progress.checkCancelled();
            out.write(domain.toSql(options, info.defaultCharacterSet()));
            out.write("\n");
            progress.extracted("Domain " + domain.name());
        }

        if (!generators.isEmpty()) {
            out.write("\n/* Generators */\n\n");
        }
        try (Statement statement = connection.createStatement()) {
            for (String generator : generators.keySet()) {
                progress.checkCancelled();
                String quoted = options.quote(generator);
                out.write("CREATE GENERATOR " + quoted + ";\n");
                out.write("SET GENERATOR " + quoted + " TO " + currentValue(statement, generator) + ";\n");
                progress.extracted("Generator " + generator);
            }
        }

        writeTables(out, tables, options, info, progress);
        writeIndexesAndConstraints(out, tables, indexes, options, progress);
        writeDescriptions(out, domains, generators, options);
        out.flush();
    }

    /** Fase de tabelas: sem FKs nem checks, só chaves primárias e únicas, que as FKs precisam já existir. */
    private static void writeTables(Writer out, List<TableCatalog.Table> tables, ExtractionOptions options,
            DatabaseInfo info, ExtractionProgress progress) throws IOException {
        if (tables.isEmpty()) {
            return;
        }
        out.write("\n/* Tables */\n\n");
        for (TableCatalog.Table table : tables) {
            progress.checkCancelled();
            out.write(table.toSql(options, info.defaultCharacterSet()) + "\n\n");
            progress.extracted("Table " + table.name());
        }
        List<String> keys = new ArrayList<>();
        for (TableCatalog.Table table : tables) {
            for (TableCatalog.Constraint constraint : table.constraints()) {
                if (constraint.isKey()) {
                    keys.add(constraint.toSql(options));
                }
            }
        }
        writeBlock(out, "Primary keys and unique constraints", keys);
    }

    /**
     * Fase de índices e FKs, depois de todas as tabelas: colunas {@code COMPUTED BY} separadas, índices, checks e FKs,
     * nessa ordem, e por fim o ponto de partida das identities. Assim a ordem das tabelas não importa e ciclos de FK
     * não quebram o script.
     */
    private static void writeIndexesAndConstraints(Writer out, List<TableCatalog.Table> tables,
            List<TableCatalog.Index> indexes, ExtractionOptions options, ExtractionProgress progress)
            throws IOException {
        List<String> computed = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        List<String> foreignKeys = new ArrayList<>();
        List<String> restarts = new ArrayList<>();
        for (TableCatalog.Table table : tables) {
            if (options.computedBySeparate()) {
                for (TableCatalog.Column column : table.computedColumns()) {
                    computed.add("ALTER TABLE " + options.quote(table.name()) + " ADD "
                            + column.definition(options, null) + ";");
                }
            }
            for (TableCatalog.Constraint constraint : table.constraints()) {
                if (constraint.kind().equals("CHECK")) {
                    checks.add(constraint.toSql(options));
                } else if (!constraint.isKey()) {
                    foreignKeys.add(constraint.toSql(options));
                }
            }
            for (TableCatalog.Column column : table.identityColumns()) {
                Long restart = column.identity().restartWith();
                if (restart != null) {
                    restarts.add("ALTER TABLE " + options.quote(table.name()) + " ALTER COLUMN "
                            + options.quote(column.name()) + " RESTART WITH " + restart + ";");
                }
            }
        }
        writeBlock(out, "Computed columns", computed);
        if (!indexes.isEmpty()) {
            out.write("\n/* Indices */\n\n");
        }
        for (TableCatalog.Index index : indexes) {
            progress.checkCancelled();
            out.write(index.toSql(options) + "\n");
            if (index.inactive()) {
                out.write(index.inactiveSql(options) + "\n");
            }
            progress.extracted("Index " + index.name());
        }
        writeBlock(out, "Check constraints", checks);
        writeBlock(out, "Foreign keys", foreignKeys);
        writeBlock(out, "Identity columns", restarts);
    }

    private static void writeBlock(Writer out, String title, List<String> statements) throws IOException {
        if (statements.isEmpty()) {
            return;
        }
        out.write("\n/* " + title + " */\n\n");
        for (String statement : statements) {
            out.write(statement + "\n");
        }
    }

    /** Sem usuário nem senha (a senha nunca vai para o arquivo) e sempre com o charset padrão (ADR 0009). */
    private String createDatabase(DatabaseInfo info) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT MON$DATABASE_NAME, MON$PAGE_SIZE FROM MON$DATABASE")) {
            rs.next();
            return "CREATE DATABASE '" + rs.getString(1).strip().replace("'", "''") + "' PAGE_SIZE " + rs.getInt(2)
                    + " DEFAULT CHARACTER SET " + info.defaultCharacterSet()
                    + (info.defaultCollation() == null ? "" : " COLLATION " + info.defaultCollation()) + ";";
        }
    }

    private void warnIfConnectionCharsetDiffers(DatabaseInfo info, ExtractionProgress progress) {
        try {
            String used = DatabaseInfo.connectionCharacterSet(connection);
            if (used != null && !used.equals(info.defaultCharacterSet())) {
                progress.warn("A conexão usa o charset " + used + ", e o banco usa " + info.defaultCharacterSet()
                        + ". Textos de colunas NONE ou de outro charset podem sair trocados; ajuste o charset da conexão"
                        + " para o do banco (ADR 0009).");
            }
        } catch (SQLException e) {
            // MON$ pode estar fora do alcance do usuário: sem a informação, não há aviso.
        }
    }

    /**
     * A seleção é literal: nada é cortado, mas uma FK ou coluna que depende de objeto fora dela só roda num banco que
     * já o tenha, e o usuário precisa saber.
     */
    private static void warnAboutMissingDependencies(List<Domain> domains, List<TableCatalog.Table> tables,
            ExtractionProgress progress) {
        Set<String> tableNames = tables.stream().map(TableCatalog.Table::name).collect(Collectors.toSet());
        Set<String> domainNames = domains.stream().map(Domain::name).collect(Collectors.toSet());
        Set<String> missing = new TreeSet<>();
        for (TableCatalog.Table table : tables) {
            for (TableCatalog.Constraint constraint : table.constraints()) {
                if (constraint.refTable() != null && !tableNames.contains(constraint.refTable())) {
                    missing.add("tabela " + constraint.refTable() + " (FK " + constraint.name() + ")");
                }
            }
            for (TableCatalog.Column column : table.columns()) {
                if (column.domain() != null && !domainNames.contains(column.domain())) {
                    missing.add("domínio " + column.domain() + " (coluna " + table.name() + "." + column.name() + ")");
                }
            }
        }
        if (!missing.isEmpty()) {
            progress.warn("O script usa objetos que não estão na seleção: " + String.join(", ", missing)
                    + ". Eles precisam existir no banco onde o script for executado.");
        }
    }

    /** Com o banco em {@code NONE} não há o que avisar: o banco recriado também não terá charset. */
    private static void warnAboutNoneColumns(DatabaseInfo info, List<Domain> domains, List<TableCatalog.Table> tables,
            ExtractionProgress progress) {
        if (info.defaultCharacterSet().equals("NONE")) {
            return;
        }
        List<String> none = new ArrayList<>();
        for (Domain domain : domains) {
            if (domain.type().isNoneText()) {
                none.add(domain.name());
            }
        }
        for (TableCatalog.Table table : tables) {
            for (TableCatalog.Column column : table.columns()) {
                if (column.type() != null && column.type().isNoneText()) {
                    none.add(table.name() + "." + column.name());
                }
            }
        }
        if (!none.isEmpty()) {
            progress.warn("Domínios ou colunas com CHARACTER SET NONE (" + String.join(", ", none) + "): o servidor"
                    + " não sabe o charset real desses textos. Eles saem byte a byte no charset do banco ("
                    + info.defaultCharacterSet() + "); confira a codificação ao abrir ou rodar o script (ADR 0009).");
        }
    }

    private static void writeDescriptions(Writer out, List<Domain> domains, Map<String, String> generators,
            ExtractionOptions options) throws IOException {
        boolean header = false;
        for (Domain domain : domains) {
            if (domain.description() != null) {
                header = descriptionsHeader(out, header);
                out.write(comment("DOMAIN", domain.name(), domain.description(), options));
            }
        }
        for (Map.Entry<String, String> generator : generators.entrySet()) {
            if (generator.getValue() != null) {
                header = descriptionsHeader(out, header);
                out.write(comment("GENERATOR", generator.getKey(), generator.getValue(), options));
            }
        }
    }

    private static boolean descriptionsHeader(Writer out, boolean written) throws IOException {
        if (!written) {
            out.write("\n/* Descriptions */\n\n");
        }
        return true;
    }

    private static String comment(String kind, String name, String text, ExtractionOptions options) {
        return "COMMENT ON " + kind + " " + options.quote(name) + " IS '" + text.replace("'", "''") + "';\n";
    }

    private List<Domain> readDomains(Selection selection, ExtractionProgress progress) throws SQLException {
        Map<String, String> dimensions = Rdb.dimensions(connection);
        List<Domain> domains = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(DOMAINS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString("RDB$FIELD_NAME").strip();
                if (!selection.includesDomain(name)) {
                    continue;
                }
                FieldType type = new FieldType(rs.getInt("RDB$FIELD_TYPE"), rs.getInt("RDB$FIELD_SUB_TYPE"),
                        rs.getInt("RDB$FIELD_LENGTH"), Rdb.nullableInt(rs, "RDB$CHARACTER_LENGTH"),
                        Rdb.nullableInt(rs, "RDB$FIELD_PRECISION"), rs.getInt("RDB$FIELD_SCALE"),
                        Rdb.nullableInt(rs, "RDB$SEGMENT_LENGTH"), Rdb.trimmed(rs.getString("RDB$CHARACTER_SET_NAME")),
                        Rdb.trimmed(rs.getString("RDB$COLLATION_NAME")), dimensions.get(name));
                domains.add(new Domain(name, type, Rdb.source(rs.getString("RDB$DEFAULT_SOURCE")),
                        rs.getInt("RDB$NULL_FLAG") == 1, Rdb.source(rs.getString("RDB$VALIDATION_SOURCE")),
                        Rdb.source(rs.getString("RDB$DESCRIPTION"))));
            }
        }
        return domains;
    }

    private Map<String, String> readGenerators(Selection selection) throws SQLException {
        Map<String, String> generators = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(GENERATORS)) {
            while (rs.next()) {
                String name = rs.getString(1).strip();
                if (selection.includesGenerator(name)) {
                    generators.put(name, Rdb.source(rs.getString(2)));
                }
            }
        }
        return generators;
    }

    private static long currentValue(Statement statement, String generator) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT GEN_ID(\"" + generator.replace("\"", "\"\"") + "\", 0) FROM RDB$DATABASE")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Um domínio do usuário; {@code defaultSource} e {@code checkSource} já trazem a palavra-chave ({@code DEFAULT}, {@code CHECK}). */
    private record Domain(String name, FieldType type, String defaultSource, boolean notNull, String checkSource,
            String description) {

        String toSql(ExtractionOptions options, String defaultCharacterSet) {
            StringBuilder sql = new StringBuilder("CREATE DOMAIN ").append(options.quote(name)).append(" AS ")
                    .append(type.toSql(defaultCharacterSet, options.alwaysCharacterSet()));
            if (defaultSource != null) {
                sql.append(' ').append(defaultSource);
            }
            if (notNull) {
                sql.append(" NOT NULL");
            }
            if (checkSource != null) {
                sql.append(' ').append(checkSource);
            }
            return sql.append(';').toString();
        }
    }
}
