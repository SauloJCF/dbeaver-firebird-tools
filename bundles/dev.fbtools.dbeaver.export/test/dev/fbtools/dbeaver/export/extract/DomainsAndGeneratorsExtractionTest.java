package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.fbtools.dbeaver.export.testing.AllowedDiffs;
import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;
import dev.fbtools.dbeaver.export.testing.ObjectKey;
import dev.fbtools.dbeaver.export.testing.ScriptComparator;

/**
 * Issue 02: domínios e generators do banco sintético contra a Referência do IBExpert, ida e volta, cancelamento e
 * charset {@code WIN1252}. Exige o Firebird 5 local (ADR 0008).
 */
class DomainsAndGeneratorsExtractionTest {

    private static final Set<String> KINDS = Set.of("DOMAIN", "GENERATOR", "DESCRIPTION");
    private static final String ISQL = "C:\\Program Files\\Firebird\\Firebird_5_0\\isql.exe";

    private static ExportTestDatabase synthetic;
    private static String script;

    @BeforeAll
    static void createSyntheticDatabase() throws Exception {
        synthetic = ExportTestDatabase.syntheticDatabase("synthetic");
        script = extract(synthetic);
    }

    @Test
    void scriptHasCharsetHeaderAndEveryDomainAndGenerator() {
        assertTrue(script.startsWith("SET SQL DIALECT 3;\nSET NAMES UTF8;\n"), script);
        assertEquals(13, count("CREATE DOMAIN "));
        assertEquals(6, count("CREATE GENERATOR "));
        assertEquals(6, count("SET GENERATOR "));
        assertFalse(script.contains("PASSWORD"), "senha nunca entra no arquivo");
    }

    @Test
    void scriptMatchesTheIbexpertReferenceExceptForAllowedDiffs() throws IOException {
        Path samples = ExportTestDatabase.repositoryRoot().resolve("samples/export-db/golden");
        String reference = Files.readString(samples.resolve("export-db.ibexpert.sql"), StandardCharsets.UTF_8);
        AllowedDiffs allowed = AllowedDiffs.load(samples.resolve("allowed-diffs"));
        List<ScriptComparator.Difference> differences = ScriptComparator.compare(reference, script, KINDS, allowed).stream()
                .filter(DomainsAndGeneratorsExtractionTest::inScope).toList();
        assertTrue(differences.isEmpty(), () -> "Divergências da Referência:\n" + differences);
        assertEquals(Set.of(), allowed.unused(), "allowed-diffs com entradas sem uso");
    }

    @Test
    void roundTripGivesTheSameScript() throws Exception {
        ExportTestDatabase empty = ExportTestDatabase.create("roundtrip", "UTF8");
        empty.run(script);
        assertEquals(script, extract(empty));
    }

    @Test
    void scriptRunsInIsqlOnAnEmptyDatabase(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of(ISQL)), "isql do Firebird 5 não encontrado");
        ExportTestDatabase empty = ExportTestDatabase.create("isql", "UTF8");
        Path file = temp.resolve("script.sql");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        Path wrapper = temp.resolve("run.sql");
        Files.writeString(wrapper, "SET NAMES UTF8;\nCONNECT '" + empty.host() + ":" + empty.path() + "' USER '"
                + empty.user() + "' PASSWORD '" + empty.password() + "';\nINPUT '" + file + "';\n",
                StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(ISQL, "-q", "-i", wrapper.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertFalse(output.contains("Statement failed") || output.contains("Use CONNECT or CREATE DATABASE"), output);
    }

    @Test
    void cancelRemovesThePartialFile(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("partial.sql");
        ExtractionProgress cancelAfterThree = new ExtractionProgress() {
            private int extracted;

            @Override
            public void extracted(String description) {
                extracted++;
            }

            @Override
            public boolean isCancelled() {
                return extracted >= 3;
            }
        };
        try (Connection connection = synthetic.connect()) {
            assertThrows(ExtractionCancelledException.class,
                    () -> ExtractionFile.write(connection, Selection.all(), file, null, null, cancelAfterThree));
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void onlySelectedObjectsAreExtracted() throws Exception {
        try (Connection connection = synthetic.connect()) {
            StringWriter out = new StringWriter();
            new MetadataExtractor(connection).extract(new Selection(Set.of("D_ID"), Set.of("GEN_PEDIDO")), out, null,
                    ExtractionProgress.NONE);
            assertEquals(1, count(out.toString(), "CREATE DOMAIN "));
            assertEquals(1, count(out.toString(), "CREATE GENERATOR "));
            assertTrue(out.toString().contains("SET GENERATOR GEN_PEDIDO TO 1000;"));
        }
    }

    @Test
    void win1252DatabaseKeepsAccentsInNamesDescriptionsAndDefaults(@TempDir Path temp) throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("win1252", "WIN1252");
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DOMAIN \"D_AÇÃO\" AS VARCHAR(30) DEFAULT 'açúcar' NOT NULL");
            statement.execute("CREATE DOMAIN D_LEGADO AS CHAR(5) CHARACTER SET NONE");
            statement.execute("COMMENT ON DOMAIN \"D_AÇÃO\" IS 'Ação do usuário'");
            statement.execute("CREATE GENERATOR \"GEN_SEQÜÊNCIA\"");
            statement.execute("COMMENT ON GENERATOR \"GEN_SEQÜÊNCIA\" IS 'Sequência única'");
        }
        Path file = temp.resolve("win1252.sql");
        try (Connection connection = source.connect()) {
            ExtractionFile.write(connection, Selection.all(), file, null, null, ExtractionProgress.NONE);
        }
        String text = Files.readString(file, Charset.forName("windows-1252"));
        assertTrue(text.contains("SET NAMES WIN1252;"), text);
        assertTrue(text.contains("CREATE DOMAIN \"D_AÇÃO\" AS VARCHAR(30) DEFAULT 'açúcar' NOT NULL;"), text);
        assertTrue(text.contains("CHAR(5) CHARACTER SET NONE"), text);
        assertTrue(text.contains("COMMENT ON DOMAIN \"D_AÇÃO\" IS 'Ação do usuário';"), text);
        assertTrue(text.contains("COMMENT ON GENERATOR \"GEN_SEQÜÊNCIA\" IS 'Sequência única';"), text);

        ExportTestDatabase target = ExportTestDatabase.create("win1252-copy", "WIN1252");
        target.run(text);
        assertEquals(text, extract(target));
    }

    /** As descrições de tabelas, rotinas etc. ficam para os issues seguintes: só domínios e generators contam aqui. */
    private static boolean inScope(ScriptComparator.Difference difference) {
        ObjectKey key = difference.key();
        return !key.kind().equals("DESCRIPTION") || key.name().startsWith("DOMAIN ") || key.name().startsWith("GENERATOR ");
    }

    @Test
    void warnsWhenTheConnectionCharsetDiffersFromTheDatabase() throws Exception {
        ExportTestDatabase win1252 = ExportTestDatabase.create("win1252-warn", "WIN1252");
        List<String> warnings = new java.util.ArrayList<>();
        try (Connection connection = win1252.connect("UTF8")) {
            new MetadataExtractor(connection).extract(Selection.all(), new StringWriter(), null,
                    new ExtractionProgress() {
                        @Override
                        public void warn(String message) {
                            warnings.add(message);
                        }
                    });
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("UTF8") && warnings.get(0).contains("WIN1252"), warnings.get(0));
    }

    private static String extract(ExportTestDatabase database) throws SQLException, IOException {
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new MetadataExtractor(connection).extract(Selection.all(), out, null, ExtractionProgress.NONE);
            return out.toString();
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    private static int count(String needle) {
        return count(script, needle);
    }
}
