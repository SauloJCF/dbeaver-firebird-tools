package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
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
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.fbtools.dbeaver.export.testing.AllowedDiffs;
import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;
import dev.fbtools.dbeaver.export.testing.ObjectKey;
import dev.fbtools.dbeaver.export.testing.ScriptSplitter;
import dev.fbtools.dbeaver.export.testing.StatementNormalizer;

/**
 * Issue 08: o Script de dados. O banco sintético contra a Referência de dados do IBExpert, ida e volta do conteúdo
 * das tabelas (com as FKs ativas), os tipos do Firebird 5, cada opção, a divisão em arquivos, o charset (ADR 0009), o
 * cancelamento e o streaming. Exige o Firebird 5 local (ADR 0008).
 */
class DataExtractionTest {

    /** Pais antes das filhas: a ordem de FK automática é do issue 09, aqui a ordem é a da lista. */
    private static final List<String> SYNTHETIC_TABLES = List.of("CATEGORIA", "CLIENTE", "PEDIDO", "PRODUTO",
            "ITEM_PEDIDO", "LOG", "TIPOS_VARIOS");

    @TempDir
    Path temp;

    private static String dataScript(ExportTestDatabase database, List<String> tables, DataOptions options,
            List<String> warnings) throws Exception {
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new DataExtractor(connection).extract(tables, options, out, null, collecting(warnings));
            return out.toString();
        }
    }

    private static ExtractionProgress collecting(List<String> warnings) {
        return new ExtractionProgress() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        };
    }

    private static String metadataScript(ExportTestDatabase database) throws Exception {
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new MetadataExtractor(connection).extract(Selection.all(), ExtractionOptions.DEFAULT, out, null,
                    ExtractionProgress.NONE);
            return out.toString();
        }
    }

    /** Os INSERTs normalizados por tabela, na ordem em que aparecem. */
    private static Map<String, List<String>> insertsByTable(String script) {
        Map<String, List<String>> byTable = new TreeMap<>();
        for (String statement : ScriptSplitter.split(script)) {
            String normalized = StatementNormalizer.normalize(statement);
            if (normalized.startsWith("INSERT INTO ")) {
                byTable.computeIfAbsent(normalized.split(" ")[2], t -> new ArrayList<>()).add(normalized);
            }
        }
        return byTable;
    }

    /** O conteúdo de todas as tabelas do usuário, texto a texto, para comparar dois bancos. */
    private static Map<String, List<String>> contents(ExportTestDatabase database) throws SQLException {
        Map<String, List<String>> contents = new LinkedHashMap<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            // Como o plugin: sem isso, um servidor com DataTypeCompatibility antigo estoura no INT128 e perde fuso e DECFLOAT.
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

    /** Os triggers do banco recém-criado não podem mexer nos dados que o script grava (o IBExpert grava antes deles). */
    private static void deactivateTriggers(ExportTestDatabase database) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT RDB$TRIGGER_NAME FROM RDB$TRIGGERS "
                    + "WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$RELATION_NAME IS NOT NULL")) {
                while (rs.next()) {
                    names.add(rs.getString(1).strip());
                }
            }
            for (String name : names) {
                statement.execute("ALTER TRIGGER \"" + name + "\" INACTIVE");
            }
        }
    }

    @Test
    void dataMatchesTheIbexpertReferenceExceptForAllowedDiffs() throws Exception {
        Path golden = ExportTestDatabase.repositoryRoot().resolve("samples/export-db/golden");
        String reference = Files.readString(golden.resolve("export-db.ibexpert.sql"), StandardCharsets.UTF_8);
        AllowedDiffs allowed = AllowedDiffs.load(golden.resolve("allowed-diffs"));
        ExportTestDatabase database = ExportTestDatabase.syntheticDatabase("data-reference");
        Map<String, List<String>> expected = insertsByTable(reference);
        Map<String, List<String>> actual = insertsByTable(dataScript(database, SYNTHETIC_TABLES,
                DataOptions.DEFAULT, new ArrayList<>()));
        List<String> differences = new ArrayList<>();
        assertEquals(expected.keySet(), actual.keySet());
        for (String table : expected.keySet()) {
            if (!expected.get(table).equals(actual.get(table)) && !allowed.allows(new ObjectKey("DATA", table))) {
                differences.add(table + "\n  esperado: " + expected.get(table) + "\n  obtido:   "
                        + actual.get(table));
            }
        }
        assertTrue(differences.isEmpty(), () -> "Divergências da Referência de dados:\n" + differences);
        List<String> unused = allowed.unused().stream().filter(entry -> entry.startsWith("DATA ")).toList();
        assertEquals(List.of(), unused, "allowed-diffs com entradas sem uso");
    }

    /** A diferença aceita vale para a tabela inteira; este teste garante que ela é só a das frações de segundo. */
    @Test
    void tiposVariosDiffersFromTheReferenceOnlyInTheFractionsOfASecond() throws Exception {
        Path golden = ExportTestDatabase.repositoryRoot().resolve("samples/export-db/golden");
        String reference = Files.readString(golden.resolve("export-db.ibexpert.sql"), StandardCharsets.UTF_8);
        ExportTestDatabase database = ExportTestDatabase.syntheticDatabase("data-reference-fractions");
        List<String> expected = insertsByTable(reference).get("TIPOS_VARIOS").stream()
                .map(sql -> sql.replace("'23:59:59.999'", "'23:59:59.9999'")
                        .replace("'2099-12-31 23:59:59'", "'2099-12-31 23:59:59.9999'"))
                .toList();
        assertEquals(expected, insertsByTable(dataScript(database, SYNTHETIC_TABLES, DataOptions.DEFAULT,
                new ArrayList<>())).get("TIPOS_VARIOS"));
    }

    @Test
    void syntheticDataRoundTripsWithForeignKeysActive() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("data-roundtrip-source");
        DataOptions options = DataOptions.DEFAULT.withBlobs(true);
        String data = dataScript(source, SYNTHETIC_TABLES, options, new ArrayList<>());
        ExportTestDatabase target = ExportTestDatabase.create("data-roundtrip-target", "UTF8");
        target.run(metadataScript(source));
        deactivateTriggers(target);
        target.run(data);
        assertEquals(contents(source), contents(target));
        assertFalse(contents(target).get("CLIENTE").isEmpty());
    }

    @Test
    void firebird5TypesRoundTrip() throws Exception {
        String ddl = """
                CREATE TABLE TIPOS5 (
                  ID INTEGER NOT NULL PRIMARY KEY,
                  C_BOOL BOOLEAN,
                  C_DF16 DECFLOAT(16),
                  C_DF34 DECFLOAT(34),
                  C_I128 INT128,
                  C_N38 NUMERIC(38,10),
                  C_DOUBLE DOUBLE PRECISION,
                  C_FLOAT FLOAT,
                  C_TIME_TZ TIME WITH TIME ZONE,
                  C_TS_TZ TIMESTAMP WITH TIME ZONE,
                  C_TS TIMESTAMP,
                  C_TIME TIME,
                  C_OCTETS VARCHAR(8) CHARACTER SET OCTETS,
                  C_TEXT BLOB SUB_TYPE TEXT CHARACTER SET UTF8,
                  C_BIN BLOB SUB_TYPE BINARY,
                  C_GEN INTEGER GENERATED ALWAYS AS IDENTITY,
                  C_CALC COMPUTED BY (ID * 2)
                );
                """;
        ExportTestDatabase source = ExportTestDatabase.create("data-types-source", "UTF8");
        source.run(ddl);
        source.run("""
                INSERT INTO TIPOS5 (ID, C_BOOL, C_DF16, C_DF34, C_I128, C_N38, C_DOUBLE, C_FLOAT, C_TIME_TZ, C_TS_TZ,
                                    C_TS, C_TIME, C_OCTETS, C_TEXT, C_BIN)
                VALUES (1, TRUE, 1.234567890123456E+10, 1.234567890123456789012345678901234E-10,
                        170141183460469231731687303715884105727, 1234567890123456789012345678.1234567890,
                        1E-10, 0.085, '10:30:00.1234 -03:00', '2024-01-15 10:30:00.5 America/Sao_Paulo',
                        '2099-12-31 23:59:59.9999', '23:59:59.9999', x'00ff10', 'texto ação ''x''', x'DEADBEEF');
                INSERT INTO TIPOS5 (ID, C_BOOL, C_I128) VALUES (2, FALSE, -170141183460469231731687303715884105728);
                INSERT INTO TIPOS5 (ID) VALUES (3);
                """);
        DataOptions options = DataOptions.DEFAULT.withBlobs(true);
        String data = dataScript(source, List.of("TIPOS5"), options, new ArrayList<>());
        assertTrue(data.contains("OVERRIDING SYSTEM VALUE"), data);
        assertFalse(data.contains("C_CALC"), data);
        ExportTestDatabase target = ExportTestDatabase.create("data-types-target", "UTF8");
        target.run(ddl);
        target.run(data);
        assertEquals(contents(source), contents(target));
    }

    @Test
    void optionsChangeTheLiterals() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("data-options", "UTF8");
        source.run("""
                CREATE TABLE T (ID INTEGER, TXT CHAR(6), D DATE, TS TIMESTAMP, B BLOB SUB_TYPE TEXT);
                INSERT INTO T VALUES (1, 'ab', '2024-03-10', '2024-03-10 08:05:30', 'blob');
                """);
        String plain = dataScript(source, List.of("T"), DataOptions.DEFAULT, new ArrayList<>());
        assertTrue(plain.contains("INSERT INTO T (ID, TXT, D, TS) VALUES (1, 'ab    ', '2024-03-10', "
                + "'2024-03-10 08:05:30');"), plain);
        List<String> warnings = new ArrayList<>();
        dataScript(source, List.of("T"), DataOptions.DEFAULT, warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("T.B")), warnings.toString());

        DataOptions custom = DataOptions.DEFAULT.withAnsiPrefix(true).withTexts(true, false).withBlobs(true)
                .withFormats("dd.MM.yyyy", "dd.MM.yyyy HH:mm");
        String formatted = dataScript(source, List.of("T"), custom, new ArrayList<>());
        assertTrue(formatted.contains("VALUES (1, 'ab', DATE '10.03.2024', TIMESTAMP '10.03.2024 08:05', 'blob');"),
                formatted);
        assertTrue(dataScript(source, List.of("T"), DataOptions.DEFAULT.withOmitHeader(true), new ArrayList<>())
                .startsWith("\n/* Data: T */"), plain);
        assertTrue(plain.startsWith("SET SQL DIALECT 3;\nSET NAMES UTF8;\n"), plain);
    }

    @Test
    void commitWorkEveryNRowsAndAtTheEndOfEachTable() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("data-commit", "UTF8");
        source.run("CREATE TABLE A (ID INTEGER);\nCREATE TABLE B (ID INTEGER);\nCREATE TABLE VAZIA (ID INTEGER);");
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("EXECUTE BLOCK AS DECLARE I INTEGER = 1; BEGIN WHILE (I <= 25) DO BEGIN "
                    + "INSERT INTO A VALUES (:I); I = I + 1; END END");
            statement.execute("INSERT INTO B VALUES (1)");
        }
        String script = dataScript(source, List.of("A", "B", "VAZIA"), DataOptions.DEFAULT.withCommitEvery(10),
                new ArrayList<>());
        // A: 10, 20 e os 5 últimos; B: 1; VAZIA não escreve nada.
        assertEquals(4, script.split("COMMIT WORK;", -1).length - 1, script);
        assertFalse(script.contains("VAZIA"), script);
        assertTrue(script.indexOf("(10);") < script.indexOf("COMMIT WORK;"), script);
        assertTrue(script.contains("INSERT INTO A (ID) VALUES (11);\n"), script);
    }

    @Test
    void largeScriptsSplitIntoNumberedFilesThatRunInSequence() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("data-split", "UTF8");
        String ddl = "CREATE TABLE A (ID INTEGER, TXT VARCHAR(40));\nCREATE TABLE B (ID INTEGER, TXT VARCHAR(40));";
        source.run(ddl);
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("EXECUTE BLOCK AS DECLARE I INTEGER = 1; BEGIN WHILE (I <= 100) DO BEGIN "
                    + "INSERT INTO A VALUES (:I, 'linha ' || :I); INSERT INTO B VALUES (:I, 'outra ' || :I); "
                    + "I = I + 1; END END");
        }
        Path file = temp.resolve("dados.sql");
        List<Path> files;
        try (Connection connection = source.connect()) {
            files = DataFile.write(connection, List.of("A", "B"),
                    DataOptions.DEFAULT.withCommitEvery(10).withMaxFileBytes(1500), file, StandardCharsets.UTF_8,
                    "UTF8", ExtractionProgress.NONE);
        }
        assertTrue(files.size() > 3, files.toString());
        assertEquals(temp.resolve("dados_001.sql"), files.get(0));
        assertEquals(temp.resolve("dados_002.sql"), files.get(1));
        ExportTestDatabase target = ExportTestDatabase.create("data-split-target", "UTF8");
        target.run(ddl);
        for (Path part : files) {
            String text = Files.readString(part, StandardCharsets.UTF_8);
            assertTrue(text.startsWith("SET SQL DIALECT 3;\nSET NAMES UTF8;\n"), part.toString());
            assertTrue(text.stripTrailing().endsWith("COMMIT WORK;"), part.toString());
            target.run(text);
        }
        assertFalse(Files.exists(file), "com limite, só os arquivos numerados existem");
        assertEquals(contents(source), contents(target));
    }

    @Test
    void withoutALimitTheScriptIsASingleFileWithTheGivenName() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("data-single");
        Path file = temp.resolve("dados.sql");
        try (Connection connection = source.connect()) {
            List<Path> files = DataFile.write(connection, SYNTHETIC_TABLES, DataOptions.DEFAULT, file, null, null,
                    ExtractionProgress.NONE);
            assertEquals(List.of(file), files);
        }
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("INSERT INTO CATEGORIA"));
    }

    @Test
    void win1252DatabaseWithNoneColumnsKeepsItsAccentsAndWarns() throws Exception {
        String ddl = "CREATE TABLE TEXTOS (ID INTEGER NOT NULL PRIMARY KEY, CURTO VARCHAR(20) CHARACTER SET NONE, "
                + "FIXO CHAR(8) CHARACTER SET NONE, NORMAL VARCHAR(20));";
        ExportTestDatabase source = ExportTestDatabase.create("data-win1252", "WIN1252");
        source.run(ddl);
        source.run("INSERT INTO TEXTOS VALUES (1, 'ação ÇÃO é', 'ñ ü', 'Café & Açúcar');\n"
                + "INSERT INTO TEXTOS VALUES (2, 'D''Ávila', NULL, 'àèìòù');");
        List<String> warnings = new ArrayList<>();
        Path file = temp.resolve("win1252.sql");
        try (Connection connection = source.connect()) {
            DataFile.write(connection, List.of("TEXTOS"), DataOptions.DEFAULT, file, null, null,
                    collecting(warnings));
        }
        assertTrue(warnings.stream().anyMatch(w -> w.contains("NONE") && w.contains("TEXTOS.CURTO")
                && w.contains("TEXTOS.FIXO") && !w.contains("TEXTOS.NORMAL")), warnings.toString());
        byte[] bytes = Files.readAllBytes(file);
        String asWindows1252 = new String(bytes, Charset.forName("windows-1252"));
        assertTrue(asWindows1252.startsWith("SET SQL DIALECT 3;\nSET NAMES WIN1252;\n"), asWindows1252);
        assertTrue(asWindows1252.contains("'ação ÇÃO é'"), asWindows1252);
        ExportTestDatabase target = ExportTestDatabase.create("data-win1252-target", "WIN1252");
        target.run(ddl);
        target.run(asWindows1252);
        assertEquals(contents(source), contents(target));
    }

    @Test
    void charactersTheFileEncodingCannotHoldAreReportedNotLostInSilence() throws Exception {
        // O servidor já recusa transliterar para um charset que não cobre o texto; o que sobra é um arquivo mais estreito que o banco.
        ExportTestDatabase source = ExportTestDatabase.create("data-lossy", "UTF8");
        source.run("CREATE TABLE POLONES (ID INTEGER, NOME VARCHAR(20) CHARACTER SET UTF8);\n"
                + "CREATE TABLE FRANCES (ID INTEGER, NOME VARCHAR(20) CHARACTER SET UTF8);");
        try (Connection connection = source.connect("UTF8"); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO POLONES VALUES (1, 'Łódź')");
            statement.execute("INSERT INTO FRANCES VALUES (1, 'Café')");
        }
        List<String> warnings = new ArrayList<>();
        Path file = temp.resolve("lossy.sql");
        try (Connection connection = source.connect()) {
            DataFile.write(connection, List.of("POLONES", "FRANCES"), DataOptions.DEFAULT, file,
                    StandardCharsets.ISO_8859_1, null,
                    collecting(warnings));
        }
        assertTrue(warnings.stream().anyMatch(w -> w.contains("POLONES") && w.contains("?")
                && !w.contains("FRANCES")), warnings.toString());
    }

    @Test
    void anUnreadableTableNamesItselfInTheError() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("data-array", "UTF8");
        source.run("CREATE TABLE COM_VETOR (ID INTEGER, V INTEGER [1:3]);\nINSERT INTO COM_VETOR (ID) VALUES (1);");
        try (Connection connection = source.connect()) {
            SQLException error = assertThrows(SQLException.class, () -> new DataExtractor(connection)
                    .extract(List.of("COM_VETOR"), DataOptions.DEFAULT, new StringWriter(), null,
                            ExtractionProgress.NONE));
            assertTrue(error.getMessage().contains("COM_VETOR"), error.getMessage());
        }
    }

    @Test
    void cancellingStopsTheFetchAndRemovesThePartialFile() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("data-cancel", "UTF8");
        source.run("CREATE TABLE GRANDE (ID INTEGER);");
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("EXECUTE BLOCK AS DECLARE I INTEGER = 1; BEGIN WHILE (I <= 5000) DO BEGIN "
                    + "INSERT INTO GRANDE VALUES (:I); I = I + 1; END END");
        }
        Path file = temp.resolve("cancelado.sql");
        int[] polls = {0};
        ExtractionProgress cancelAfter = new ExtractionProgress() {
            @Override
            public boolean isCancelled() {
                return ++polls[0] > 1500;
            }
        };
        try (Connection connection = source.connect()) {
            assertThrows(ExtractionCancelledException.class, () -> DataFile.write(connection, List.of("GRANDE"),
                    DataOptions.DEFAULT.withCommitEvery(100).withMaxFileBytes(5000), file, StandardCharsets.UTF_8,
                    "UTF8", cancelAfter));
        }
        try (var stream = Files.list(temp)) {
            assertEquals(List.of(), stream.toList(), "nenhum arquivo parcial deve sobrar");
        }
        assertTrue(polls[0] < 1600, "o fetch parou logo depois do cancelamento: " + polls[0]);
    }

    @Test
    void aTableWithMillionsOfRowsIsStreamedWithoutKeepingItInMemory() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("data-million", "UTF8");
        source.run("CREATE TABLE MILHAO (ID INTEGER, TXT VARCHAR(60));");
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("EXECUTE BLOCK AS DECLARE I INTEGER = 1; BEGIN WHILE (I <= 1000000) DO BEGIN "
                    + "INSERT INTO MILHAO VALUES (:I, 'registro número ' || :I || ' com texto para ocupar espaço'); "
                    + "I = I + 1; END END");
        }
        long[] chars = {0};
        Writer discard = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                chars[0] += length;
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws IOException {
            }
        };
        List<String> extracted = new ArrayList<>();
        try (Connection connection = source.connect()) {
            new DataExtractor(connection).extract(List.of("MILHAO"), DataOptions.DEFAULT, discard, null,
                    new ExtractionProgress() {
                        @Override
                        public void extracted(String description) {
                            extracted.add(description);
                        }
                    });
        }
        assertEquals(List.of("Data MILHAO (1000000 rows)"), extracted);
        assertTrue(chars[0] > 100_000_000L, "o script tem ~130 MB de texto: " + chars[0]);
    }
}
