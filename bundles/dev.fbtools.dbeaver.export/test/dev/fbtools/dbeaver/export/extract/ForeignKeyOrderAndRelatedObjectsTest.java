package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;

/**
 * Issue 09: a ordem de FK do Script de dados (com ciclo) e o "Add related objects". A ordem pura roda sem banco; o
 * resto exige o Firebird 5 local (ADR 0008).
 */
class ForeignKeyOrderAndRelatedObjectsTest {


    private static ExtractionProgress collecting(List<String> warnings) {
        return new ExtractionProgress() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        };
    }

    @Test
    void parentsComeBeforeChildrenAndTiesKeepTheListOrder() {
        Map<String, Set<String>> parents = Map.of("C", Set.of("B"), "B", Set.of("A"), "D", Set.of("A"));
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of("A", "B", "C", "D"), ForeignKeyOrder.order(List.of("A", "B", "C", "D"), parents,
                collecting(warnings)));
        assertEquals(List.of(), warnings);
    }

    @Test
    void childListedFirstStillComesAfterItsParents() {
        Map<String, Set<String>> parents = Map.of("C", Set.of("B"), "B", Set.of("A"), "D", Set.of("A"));
        assertEquals(List.of("A", "D", "B", "C"),
                ForeignKeyOrder.order(List.of("C", "D", "B", "A"), parents, ExtractionProgress.NONE));
    }

    @Test
    void aParentOutsideTheListDoesNotHoldBackItsChildAndSelfReferencesAreIgnored() {
        Map<String, Set<String>> parents = Map.of("B", Set.of("A"), "C", Set.of("C"));
        assertEquals(List.of("B", "C"), ForeignKeyOrder.order(List.of("B", "C"), parents, ExtractionProgress.NONE));
    }

    @Test
    void circularForeignKeysAreWarnedAndTheRestKeepsItsOrder() {
        Map<String, Set<String>> parents = Map.of("A", Set.of("B"), "B", Set.of("A"), "C", Set.of("A"),
                "D", Set.of("D2"));
        List<String> warnings = new ArrayList<>();
        List<String> order = ForeignKeyOrder.order(List.of("C", "A", "B", "D2", "D"), parents, collecting(warnings));
        assertEquals(5, order.size());
        assertTrue(order.indexOf("D2") < order.indexOf("D"), order.toString());
        assertTrue(order.indexOf("A") < order.indexOf("C"), order.toString());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("A, B") && !warnings.get(0).contains("C"), warnings.get(0));
        assertTrue(warnings.get(0).contains("desativadas"), warnings.get(0));
    }

    @Test
    void syntheticDataComesOutInForeignKeyOrderWhateverTheListOrder() throws Exception {
        ExportTestDatabase database = ExportTestDatabase.syntheticDatabase("fk-order");
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new DataExtractor(connection).extract(List.of("TIPOS_VARIOS", "ITEM_PEDIDO", "PRODUTO", "PEDIDO",
                    "LOG", "CLIENTE", "CATEGORIA"), DataOptions.DEFAULT, out, null, ExtractionProgress.NONE);
            String script = out.toString();
            List<String> order = script.lines().filter(line -> line.startsWith("/* Data: ")).toList();
            for (String[] pair : new String[][] { { "CATEGORIA", "PRODUTO" }, { "CLIENTE", "PEDIDO" },
                    { "PEDIDO", "ITEM_PEDIDO" }, { "PRODUTO", "ITEM_PEDIDO" } }) {
                assertTrue(order.indexOf("/* Data: " + pair[0] + " */") >= 0
                        && order.indexOf("/* Data: " + pair[0] + " */") < order.indexOf("/* Data: " + pair[1] + " */"),
                        pair[0] + " antes de " + pair[1] + ": " + order);
            }
        }
    }

    @Test
    void circularForeignKeysInTheDatabaseWarnExplicitly() throws Exception {
        ExportTestDatabase database = ExportTestDatabase.create("fk-cycle", "UTF8");
        database.run("""
                CREATE TABLE T_PAI (ID INTEGER NOT NULL PRIMARY KEY, ID_FILHO INTEGER);
                CREATE TABLE T_FILHO (ID INTEGER NOT NULL PRIMARY KEY, ID_PAI INTEGER);
                ALTER TABLE T_PAI ADD CONSTRAINT FK_PAI FOREIGN KEY (ID_FILHO) REFERENCES T_FILHO (ID);
                ALTER TABLE T_FILHO ADD CONSTRAINT FK_FILHO FOREIGN KEY (ID_PAI) REFERENCES T_PAI (ID);
                CREATE TABLE T_OUTRA (ID INTEGER NOT NULL PRIMARY KEY, ID_PAI INTEGER REFERENCES T_PAI (ID));
                """);
        List<String> warnings = new ArrayList<>();
        try (Connection connection = database.connect()) {
            new DataExtractor(connection).extract(List.of("T_OUTRA", "T_FILHO", "T_PAI"), DataOptions.DEFAULT,
                    new StringWriter(), null, collecting(warnings));
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("T_FILHO, T_PAI") && !warnings.get(0).contains("T_OUTRA"),
                warnings.get(0));
    }

    @Test
    void relatedObjectsOfASingleTableRunInAnEmptyDatabase() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("related-source");
        try (Connection connection = source.connect()) {
            DependencyGraph graph = DependencyGraph.read(connection);
            Selection related = graph.related(new Selection(Set.of(), Set.of(), Set.of("ITEM_PEDIDO"), Set.of()));
            assertTrue(related.tables().containsAll(Set.of("ITEM_PEDIDO", "PEDIDO", "PRODUTO", "CLIENTE",
                    "CATEGORIA")), related.tables().toString());
            assertTrue(related.domains().contains("D_ID"), related.domains().toString());
            assertEquals(Set.of(), related.views());

            StringWriter script = new StringWriter();
            new MetadataExtractor(connection).extract(related, ExtractionOptions.DEFAULT, script, null,
                    ExtractionProgress.NONE);
            ExportTestDatabase target = ExportTestDatabase.create("related-target", "UTF8");
            target.run(script.toString());
        }
    }

    @Test
    void relatedObjectsOfAViewAndAProcedureReachTablesDomainsAndExceptions() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("related-routines");
        try (Connection connection = source.connect()) {
            DependencyGraph graph = DependencyGraph.read(connection);
            Selection view = graph.related(new Selection(Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of("V_PEDIDO_RESUMO")));
            assertTrue(view.views().contains("V_PEDIDO_TOTAL") || view.tables().contains("ITEM_PEDIDO"), view.toString());
            StringWriter viewScript = new StringWriter();
            new MetadataExtractor(connection).extract(view, ExtractionOptions.DEFAULT, viewScript, null,
                    ExtractionProgress.NONE);
            ExportTestDatabase.create("related-view-target", "UTF8").run(viewScript.toString());

            Selection procedure = graph.related(new Selection(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of("SP_FECHAR_PEDIDO"), Set.of(), Set.of()));
            StringWriter procedureScript = new StringWriter();
            new MetadataExtractor(connection).extract(procedure, ExtractionOptions.DEFAULT, procedureScript, null,
                    ExtractionProgress.NONE);
            ExportTestDatabase.create("related-proc-target", "UTF8").run(procedureScript.toString());
        }
    }

    @Test
    void relatedTablesForDataAreTheForeignKeyParentsTransitively() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("related-data");
        try (Connection connection = source.connect()) {
            DependencyGraph graph = DependencyGraph.read(connection);
            assertEquals(Set.of("ITEM_PEDIDO", "PEDIDO", "PRODUTO", "CLIENTE", "CATEGORIA"),
                    graph.tablesWithParents(List.of("ITEM_PEDIDO")));
            assertEquals(Set.of("LOG"), graph.tablesWithParents(List.of("LOG")));
        }
    }

    @Test
    void relatedKeepsATypeThatWasAlreadyEverything() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("related-all");
        try (Connection connection = source.connect()) {
            Selection related = DependencyGraph.read(connection).related(Selection.all());
            assertEquals(null, related.tables());
            assertEquals(null, related.domains());
        }
    }

}
