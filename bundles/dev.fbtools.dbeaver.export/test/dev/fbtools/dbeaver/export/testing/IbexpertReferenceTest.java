package dev.fbtools.dbeaver.export.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** A Referência do IBExpert (ADR 0008) é lida e dividida como esperado; protege a infraestrutura de comparação. */
class IbexpertReferenceTest {

    private static final Path GOLDEN = Path.of("../../samples/export-db/golden/export-db.ibexpert.sql");

    @Test
    void referenceHasEveryObjectOfTheSyntheticDatabase() throws IOException {
        Map<String, Long> countByKind = ScriptComparator.index(read(), Set.of()).keySet().stream()
                .collect(Collectors.groupingBy(ObjectKey::kind, TreeMap::new, Collectors.counting()));
        assertEquals(13L, countByKind.get("DOMAIN"));
        assertEquals(6L, countByKind.get("GENERATOR"));
        assertEquals(3L, countByKind.get("EXCEPTION"));
        assertEquals(3L, countByKind.get("PROCEDURE"));
        assertEquals(7L, countByKind.get("TABLE"));
        assertEquals(3L, countByKind.get("VIEW"));
        assertEquals(9L, countByKind.get("TRIGGER"));
        assertEquals(6L, countByKind.get("INDEX"));
        assertEquals(2L, countByKind.get("ROLE"));
        // 7 PKs, 5 FKs, 2 UNIQUE e 2 CHECK.
        assertEquals(16L, countByKind.get("CONSTRAINT"));
    }

    @Test
    void domainsAndGeneratorsOfTheReference() throws IOException {
        Map<ObjectKey, List<String>> index = ScriptComparator.index(read(), Set.of("DOMAIN", "GENERATOR"));
        assertEquals(List.of("CREATE DOMAIN D_ID AS INTEGER NOT NULL"), index.get(new ObjectKey("DOMAIN", "D_ID")));
        assertEquals(List.of("CREATE GENERATOR GEN_PEDIDO", "SET GENERATOR GEN_PEDIDO TO 1000"),
                index.get(new ObjectKey("GENERATOR", "GEN_PEDIDO")));
    }

    @Test
    void referenceComparedWithItselfHasNoDifferences() throws IOException {
        String reference = read();
        assertTrue(ScriptComparator.compare(reference, reference, Set.of(), AllowedDiffs.none()).isEmpty());
    }

    @Test
    void aChangedDomainInTheReferenceIsDetected() throws IOException {
        String reference = read();
        String changed = reference.replace("CHECK (VALUE >= 0)", "CHECK (VALUE > 0)");
        List<ScriptComparator.Difference> differences =
                ScriptComparator.compare(reference, changed, Set.of("DOMAIN"), AllowedDiffs.none());
        assertEquals(List.of(new ObjectKey("DOMAIN", "D_DINHEIRO")),
                differences.stream().map(ScriptComparator.Difference::key).toList());
    }

    private static String read() throws IOException {
        return Files.readString(GOLDEN, StandardCharsets.UTF_8);
    }
}
