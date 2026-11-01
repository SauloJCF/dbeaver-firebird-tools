package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.export.testing.AllowedDiffs;
import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;
import dev.fbtools.dbeaver.export.testing.ScriptComparator;

/**
 * Issue 04: views em ordem topológica. O banco sintético contra a Referência do IBExpert e ida e volta com cadeias
 * de views, {@code CREATE OR ALTER} e view que lê procedure. Exige o Firebird 5 local (ADR 0008).
 */
class ViewsExtractionTest {

    private static String synthetic;

    @BeforeAll
    static void extractSyntheticDatabase() throws Exception {
        synthetic = extract(ExportTestDatabase.syntheticDatabase("synthetic-views"), ExtractionOptions.DEFAULT,
                Selection.all(), ExtractionProgress.NONE);
    }

    @Test
    void viewsMatchTheIbexpertReferenceExceptForAllowedDiffs() throws IOException {
        Path golden = ExportTestDatabase.repositoryRoot().resolve("samples/export-db/golden");
        String reference = Files.readString(golden.resolve("export-db.ibexpert.sql"), StandardCharsets.UTF_8);
        AllowedDiffs allowed = AllowedDiffs.load(golden.resolve("allowed-diffs"));
        List<ScriptComparator.Difference> differences =
                ScriptComparator.compare(reference, synthetic, Set.of("VIEW"), allowed);
        assertTrue(differences.isEmpty(), () -> "Divergências da Referência:\n" + differences);
    }

    @Test
    void syntheticViewsComeAfterTheirDependencies() {
        assertTrue(synthetic.indexOf("CREATE VIEW V_PEDIDO_TOTAL") < synthetic.indexOf("CREATE VIEW V_PEDIDO_RESUMO"),
                synthetic);
        assertTrue(synthetic.lastIndexOf("CREATE TABLE") < synthetic.indexOf("CREATE VIEW V_CLIENTE_ATIVO"),
                "as views vêm depois de todas as tabelas:\n" + synthetic);
    }

    @Test
    void viewChainOfFiveLevelsIsOrderedTopologicallyAndRoundTrips() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("views-chain", "UTF8");
        // Os nomes seguem a ordem alfabética inversa da dependência: V_A lê V_B, que lê V_C, e assim por diante.
        execute(source,
                "CREATE TABLE BASE (ID INTEGER NOT NULL PRIMARY KEY, VALOR NUMERIC(9,2), NOME VARCHAR(20))",
                "CREATE VIEW V_E (ID, VALOR, NOME) AS SELECT ID, VALOR, NOME FROM BASE",
                "CREATE VIEW V_D (ID, VALOR, NOME) AS SELECT ID, VALOR, NOME FROM V_E WHERE VALOR > 0",
                "CREATE VIEW V_C (ID, VALOR) AS SELECT ID, VALOR FROM V_D",
                "CREATE VIEW V_B (ID, TOTAL) AS SELECT ID, VALOR * 2 FROM V_C",
                "CREATE VIEW V_A (ID, TOTAL, NOME) AS SELECT B.ID, B.TOTAL, D.NOME FROM V_B B JOIN V_D D ON D.ID = B.ID",
                "CREATE VIEW V_AVULSA (N) AS SELECT COUNT(*) FROM BASE");
        String script = extract(source, ExtractionOptions.DEFAULT);
        List<String> order = List.of("V_E", "V_D", "V_C", "V_B", "V_A");
        for (int i = 1; i < order.size(); i++) {
            assertTrue(script.indexOf("CREATE VIEW " + order.get(i - 1) + " ") < script.indexOf("CREATE VIEW " + order.get(i) + " "),
                    order.get(i) + " lê " + order.get(i - 1) + ":\n" + script);
        }
        assertFalse(script.contains("Views that read procedures"), script);

        ExportTestDatabase target = ExportTestDatabase.create("views-chain-copy", "UTF8");
        target.run(script);
        assertEquals(script, extract(target, ExtractionOptions.DEFAULT));
    }

    @Test
    void viewReadingASeparatedComputedColumnComesAfterTheColumn() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("views-computed", "UTF8");
        execute(source,
                "CREATE TABLE ITEM (ID INTEGER NOT NULL PRIMARY KEY, QTD INTEGER, DOBRO COMPUTED BY (QTD * 2))",
                "CREATE VIEW V_DOBRO (ID, DOBRO) AS SELECT ID, DOBRO FROM ITEM");
        ExtractionOptions options = new ExtractionOptions(false, false, true, false);
        String script = extract(source, options);
        assertTrue(script.indexOf("ALTER TABLE ITEM ADD DOBRO") < script.indexOf("CREATE VIEW V_DOBRO"), script);

        ExportTestDatabase target = ExportTestDatabase.create("views-computed-copy", "UTF8");
        target.run(script);
        assertEquals(script, extract(target, options));
    }

    @Test
    void createOrAlterOptionChangesTheStatementButNotTheMeaning() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("views-create-or-alter");
        ExtractionOptions options = new ExtractionOptions(false, false, false, false, true);
        String script = extract(source, options);
        assertTrue(script.contains("CREATE OR ALTER VIEW V_PEDIDO_RESUMO ("), script);
        assertFalse(script.contains("CREATE VIEW "), script);

        ExportTestDatabase target = ExportTestDatabase.create("views-create-or-alter-copy", "UTF8");
        target.run(script);
        assertEquals(script, extract(target, options));
        // Sem a opção, o mesmo banco recriado volta ao CREATE VIEW.
        assertEquals(synthetic, extract(target, ExtractionOptions.DEFAULT));
    }

    @Test
    void viewsThatReadSelectableProceduresComeInTheirOwnBlockAndWarn() throws Exception {
        String procedure = "CREATE PROCEDURE SP_NUMEROS RETURNS (N INTEGER) AS BEGIN N = 1; SUSPEND; N = 2; SUSPEND; END";
        ExportTestDatabase source = ExportTestDatabase.create("views-procedure", "UTF8");
        execute(source,
                "CREATE TABLE BASE (ID INTEGER NOT NULL PRIMARY KEY)",
                procedure,
                "CREATE VIEW V_NUMEROS (N) AS SELECT N FROM SP_NUMEROS",
                "CREATE VIEW V_SOBRE_NUMEROS (N) AS SELECT N FROM V_NUMEROS WHERE N > 1",
                "CREATE VIEW V_SIMPLES (ID) AS SELECT ID FROM BASE");
        List<String> warnings = new ArrayList<>();
        String script = extract(source, ExtractionOptions.DEFAULT, Selection.all(), new ExtractionProgress() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        });
        int separate = script.indexOf("/* Views that read procedures */");
        assertTrue(separate > script.indexOf("CREATE VIEW V_SIMPLES"), script);
        assertTrue(separate < script.indexOf("CREATE VIEW V_NUMEROS "), script);
        assertTrue(script.indexOf("CREATE VIEW V_NUMEROS ") < script.indexOf("CREATE VIEW V_SOBRE_NUMEROS "), script);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("procedure SP_NUMEROS (view V_NUMEROS)"), warnings.get(0));

        // As procedures só saem no issue 05: o banco de destino já as tem, e o resto do script roda sobre elas.
        ExportTestDatabase target = ExportTestDatabase.create("views-procedure-copy", "UTF8");
        execute(target, procedure);
        target.run(script);
        assertEquals(script, extract(target, ExtractionOptions.DEFAULT));
    }

    @Test
    void onlySelectedViewsAreExtractedAndMissingDependenciesWarn() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("views-selection");
        List<String> warnings = new ArrayList<>();
        String script;
        try (Connection connection = source.connect()) {
            assertEquals(List.of("V_CLIENTE_ATIVO", "V_PEDIDO_RESUMO", "V_PEDIDO_TOTAL"),
                    new MetadataExtractor(connection).listViews());
        }
        script = extract(source, ExtractionOptions.DEFAULT,
                new Selection(Set.of(), Set.of(), Set.of(), Set.of(), Set.of("V_PEDIDO_RESUMO")),
                new ExtractionProgress() {
                    @Override
                    public void warn(String message) {
                        warnings.add(message);
                    }
                });
        assertEquals(1, count(script, "CREATE VIEW "), script);
        assertTrue(script.contains("CREATE VIEW V_PEDIDO_RESUMO"), script);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("tabela ou view V_PEDIDO_TOTAL (view V_PEDIDO_RESUMO)"), warnings.get(0));
    }

    private static String extract(ExportTestDatabase database, ExtractionOptions options)
            throws SQLException, IOException {
        return extract(database, options, Selection.all(), ExtractionProgress.NONE);
    }

    private static String extract(ExportTestDatabase database, ExtractionOptions options, Selection selection,
            ExtractionProgress progress) throws SQLException, IOException {
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new MetadataExtractor(connection).extract(selection, options, out, null, progress);
            return out.toString();
        }
    }

    private static void execute(ExportTestDatabase database, String... statements) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
