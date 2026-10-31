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

/**
 * Gera o Script de metadata lendo {@code RDB$*} direto (ADR 0007). Nesta fatia: domínios e generators, na ordem das
 * Fases (domínios, depois generators com {@code SET GENERATOR}). A conexão deve usar o charset padrão do banco, para
 * que colunas {@code NONE} cheguem byte a byte (ADR 0009).
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

    /**
     * Escreve o script. Não fecha o {@code out}. {@code setNames} é o charset do {@code SET NAMES} (o do arquivo);
     * {@code null} usa o padrão do banco.
     *
     * @throws ExtractionCancelledException se {@code progress} pediu o cancelamento
     */
    public void extract(Selection selection, Writer out, String setNames, ExtractionProgress progress)
            throws SQLException, IOException {
        DatabaseInfo info = DatabaseInfo.read(connection);
        List<Domain> domains = readDomains(selection, progress);
        Map<String, String> generators = readGenerators(selection);
        warnIfConnectionCharsetDiffers(info, progress);
        progress.begin(domains.size() + generators.size());

        out.write("SET SQL DIALECT 3;\n");
        out.write("SET NAMES " + info.defaultCharacterSet() + ";\n");

        if (!domains.isEmpty()) {
            out.write("\n/* Domains */\n\n");
        }
        for (Domain domain : domains) {
            progress.checkCancelled();
            out.write(domain.toSql(info.defaultCharacterSet()));
            out.write("\n");
            progress.extracted("Domain " + domain.name());
        }

        if (!generators.isEmpty()) {
            out.write("\n/* Generators */\n\n");
        }
        try (Statement statement = connection.createStatement()) {
            for (String generator : generators.keySet()) {
                progress.checkCancelled();
                String quoted = Identifiers.quote(generator);
                out.write("CREATE GENERATOR " + quoted + ";\n");
                out.write("SET GENERATOR " + quoted + " TO " + currentValue(statement, generator) + ";\n");
                progress.extracted("Generator " + generator);
            }
        }
        writeDescriptions(out, domains, generators);
        out.flush();
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

    private static void writeDescriptions(Writer out, List<Domain> domains, Map<String, String> generators)
            throws IOException {
        boolean header = false;
        for (Domain domain : domains) {
            if (domain.description() != null) {
                header = descriptionsHeader(out, header);
                out.write(comment("DOMAIN", domain.name(), domain.description()));
            }
        }
        for (Map.Entry<String, String> generator : generators.entrySet()) {
            if (generator.getValue() != null) {
                header = descriptionsHeader(out, header);
                out.write(comment("GENERATOR", generator.getKey(), generator.getValue()));
            }
        }
    }

    private static boolean descriptionsHeader(Writer out, boolean written) throws IOException {
        if (!written) {
            out.write("\n/* Descriptions */\n\n");
        }
        return true;
    }

    private static String comment(String kind, String name, String text) {
        return "COMMENT ON " + kind + " " + Identifiers.quote(name) + " IS '" + text.replace("'", "''") + "';\n";
    }

    private List<Domain> readDomains(Selection selection, ExtractionProgress progress) throws SQLException {
        List<Domain> domains = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(DOMAINS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString("RDB$FIELD_NAME").strip();
                if (!selection.includesDomain(name)) {
                    continue;
                }
                FieldType type = new FieldType(rs.getInt("RDB$FIELD_TYPE"), rs.getInt("RDB$FIELD_SUB_TYPE"),
                        rs.getInt("RDB$FIELD_LENGTH"), nullableInt(rs, "RDB$CHARACTER_LENGTH"),
                        nullableInt(rs, "RDB$FIELD_PRECISION"), rs.getInt("RDB$FIELD_SCALE"),
                        nullableInt(rs, "RDB$SEGMENT_LENGTH"), trimmed(rs.getString("RDB$CHARACTER_SET_NAME")),
                        trimmed(rs.getString("RDB$COLLATION_NAME")));
                domains.add(new Domain(name, type, source(rs.getString("RDB$DEFAULT_SOURCE")),
                        rs.getInt("RDB$NULL_FLAG") == 1, source(rs.getString("RDB$VALIDATION_SOURCE")),
                        source(rs.getString("RDB$DESCRIPTION"))));
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
                    generators.put(name, source(rs.getString(2)));
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

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.strip();
    }

    private static String source(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Um domínio do usuário; {@code defaultSource} e {@code checkSource} já trazem a palavra-chave ({@code DEFAULT}, {@code CHECK}). */
    private record Domain(String name, FieldType type, String defaultSource, boolean notNull, String checkSource,
            String description) {

        String toSql(String defaultCharacterSet) {
            StringBuilder sql = new StringBuilder("CREATE DOMAIN ").append(Identifiers.quote(name)).append(" AS ")
                    .append(type.toSql(defaultCharacterSet, false));
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
