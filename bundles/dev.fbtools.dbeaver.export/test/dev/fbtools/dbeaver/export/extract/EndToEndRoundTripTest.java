package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.export.testing.AllowedDiffs;
import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;

/**
 * Issue 10: Ida e volta do banco sintético inteiro (todos os tipos de objeto, inclusive os do Firebird 5, e todos os
 * dados) como teste único de ponta a ponta, e a revisão do {@code allowed-diffs}. Exige o Firebird 5 local (ADR 0008).
 */
class EndToEndRoundTripTest {

    private static final Path GOLDEN = ExportTestDatabase.repositoryRoot().resolve("samples/export-db/golden");

    @Test
    void wholeSyntheticDatabaseRoundTripsMetadataAndData() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabaseWithFirebird5Objects("e2e-source");
        ExtractionOptions options = ExtractionOptions.DEFAULT;
        List<String> tables;
        try (Connection connection = source.connect()) {
            tables = new MetadataExtractor(connection).listTables();
        }
        assertFalse(tables.isEmpty());

        String metadata = metadata(source, options);
        String data = data(source, tables);
        assertNoCredentials(metadata, source);
        assertNoCredentials(data, source);

        ExportTestDatabase target = ExportTestDatabase.create("e2e-target", "UTF8");
        target.run(metadata);
        // Os INSERTs disparam os triggers de tabela: desativa para gravar os dados como estão e reativa depois.
        List<String> triggers = tableTriggers(target, "ACTIVE");
        setTriggers(target, triggers, "INACTIVE");
        target.run(data);
        setTriggers(target, triggers, "ACTIVE");

        assertEquals(metadata, metadata(target, options), "o Script de metadata do banco recriado é o mesmo");
        assertEquals(contents(source), contents(target), "o conteúdo de todas as tabelas é o mesmo");
        assertEquals(generators(source), generators(target), "os generators ficaram nos mesmos valores");
    }

    @Test
    void everyAcceptedDifferenceHasAReason() throws Exception {
        for (String line : Files.readAllLines(GOLDEN.resolve("allowed-diffs"), StandardCharsets.UTF_8)) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int bar = text.indexOf('|');
            assertTrue(bar > 0 && text.substring(bar + 1).strip().length() >= 20,
                    "Diferença aceita sem justificativa: " + text);
        }
        // O carregador também recusa entrada sem motivo.
        Path withoutReason = Files.createTempFile("allowed-diffs", ".txt");
        try {
            Files.writeString(withoutReason, "TABLE X\n");
            try {
                AllowedDiffs.load(withoutReason);
                throw new AssertionError("deveria recusar a entrada sem motivo");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("falta o motivo"), expected.getMessage());
            }
        } finally {
            Files.deleteIfExists(withoutReason);
        }
    }

    private static void assertNoCredentials(String script, ExportTestDatabase database) {
        assertFalse(script.contains(database.password()), "a senha não pode aparecer no arquivo");
        assertFalse(script.contains("PASSWORD"), "nenhum PASSWORD no arquivo");
    }

    private static String metadata(ExportTestDatabase database, ExtractionOptions options) throws Exception {
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new MetadataExtractor(connection).extract(Selection.all(), options, out, null, ExtractionProgress.NONE);
            return out.toString();
        }
    }

    private static String data(ExportTestDatabase database, List<String> tables) throws Exception {
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new DataExtractor(connection).extract(tables, DataOptions.DEFAULT.withBlobs(true), out, null,
                    ExtractionProgress.NONE);
            return out.toString();
        }
    }

    private static List<String> tableTriggers(ExportTestDatabase database, String state) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT RDB$TRIGGER_NAME FROM RDB$TRIGGERS "
                        + "WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$RELATION_NAME IS NOT NULL "
                        + "AND COALESCE(RDB$TRIGGER_INACTIVE, 0) = " + ("ACTIVE".equals(state) ? 0 : 1))) {
            while (rs.next()) {
                names.add(rs.getString(1).strip());
            }
        }
        return names;
    }

    private static void setTriggers(ExportTestDatabase database, List<String> names, String state)
            throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            for (String name : names) {
                statement.execute("ALTER TRIGGER \"" + name + "\" " + state);
            }
        }
    }

    private static Map<String, String> generators(ExportTestDatabase database) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT RDB$GENERATOR_NAME FROM RDB$GENERATORS "
                    + "WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY 1")) {
                while (rs.next()) {
                    names.add(rs.getString(1).strip());
                }
            }
            for (String name : names) {
                try (ResultSet rs = statement.executeQuery("SELECT GEN_ID(\"" + name + "\", 0) FROM RDB$DATABASE")) {
                    rs.next();
                    values.put(name, rs.getString(1));
                }
            }
        }
        return values;
    }

    /** O conteúdo de todas as tabelas do usuário, texto a texto; BLOBs e binários em hexadecimal. */
    private static Map<String, List<String>> contents(ExportTestDatabase database) throws SQLException {
        Map<String, List<String>> contents = new LinkedHashMap<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            for (String type : List.of("BOOLEAN", "DECFLOAT", "INT128", "TIME ZONE")) {
                statement.execute("SET BIND OF " + type + " TO NATIVE");
            }
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT RDB$RELATION_NAME FROM RDB$RELATIONS "
                    + "WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$VIEW_BLR IS NULL ORDER BY 1")) {
                while (rs.next()) {
                    tables.add(rs.getString(1).strip());
                }
            }
            for (String table : tables) {
                List<String> rows = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY 1")) {
                    ResultSetMetaData meta = rs.getMetaData();
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            int type = meta.getColumnType(i);
                            Object value = type == Types.BLOB || type == Types.LONGVARBINARY
                                    || type == Types.VARBINARY || type == Types.BINARY
                                    ? hex(rs.getBytes(i)) : rs.getString(i);
                            row.append(meta.getColumnLabel(i)).append('=').append(value).append(';');
                        }
                        rows.add(row.toString());
                    }
                }
                contents.put(table, rows);
            }
        }
        return contents;
    }

    private static String hex(byte[] bytes) {
        return bytes == null ? null : new java.math.BigInteger(1, bytes).toString(16) + "/" + bytes.length;
    }
}
