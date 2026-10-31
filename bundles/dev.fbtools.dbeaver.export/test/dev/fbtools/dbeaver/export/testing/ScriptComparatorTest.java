package dev.fbtools.dbeaver.export.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptComparatorTest {

    private static final Set<String> ALL = Set.of();

    @Test
    void objectOrderDoesNotMatter() {
        String a = "CREATE DOMAIN D1 AS INTEGER; CREATE GENERATOR G1;";
        String b = "create generator g1;\ncreate domain d1 as integer;";
        assertTrue(ScriptComparator.compare(a, b, ALL, AllowedDiffs.none()).isEmpty());
    }

    @Test
    void reportsMissingExtraAndDifferentObjects() {
        String expected = "CREATE DOMAIN D1 AS INTEGER; CREATE DOMAIN D2 AS DATE;";
        String actual = "CREATE DOMAIN D1 AS BIGINT; CREATE DOMAIN D3 AS DATE;";
        List<ScriptComparator.Difference> differences =
                ScriptComparator.compare(expected, actual, ALL, AllowedDiffs.none());
        assertEquals(List.of("DOMAIN D1", "DOMAIN D2", "DOMAIN D3"),
                differences.stream().map(d -> d.key().toString()).toList());
    }

    @Test
    void setGeneratorBelongsToTheGenerator() {
        assertEquals(new ObjectKey("GENERATOR", "G1"), ObjectKey.of("SET GENERATOR G1 TO 5"));
        List<ScriptComparator.Difference> differences = ScriptComparator.compare(
                "CREATE GENERATOR G1; SET GENERATOR G1 TO 5;", "CREATE GENERATOR G1; SET GENERATOR G1 TO 6;",
                ALL, AllowedDiffs.none());
        assertEquals(1, differences.size());
    }

    @Test
    void kindsFilterLimitsTheComparison() {
        List<ScriptComparator.Difference> differences = ScriptComparator.compare(
                "CREATE DOMAIN D1 AS INTEGER; CREATE GENERATOR G1;", "CREATE DOMAIN D1 AS INTEGER;",
                Set.of("DOMAIN"), AllowedDiffs.none());
        assertTrue(differences.isEmpty());
    }

    @Test
    void descriptionsInBothSyntaxesShareTheKey() {
        assertEquals(new ObjectKey("DESCRIPTION", "DOMAIN D1"), ObjectKey.of("COMMENT ON DOMAIN D1 IS 'x'"));
        assertEquals(new ObjectKey("DESCRIPTION", "DOMAIN D1"), ObjectKey.of("DESCRIBE DOMAIN D1 'x'"));
        assertEquals(new ObjectKey("DESCRIPTION", "COLUMN T.C"), ObjectKey.of("COMMENT ON COLUMN T . C IS 'x'"));
        assertEquals(new ObjectKey("DESCRIPTION", "COLUMN T.C"), ObjectKey.of("DESCRIBE FIELD C TABLE T 'x'"));
    }

    @Test
    void headerAndDataStatementsHaveNoKey() {
        assertNull(ObjectKey.of("SET NAMES UTF8"));
        assertNull(ObjectKey.of("CREATE DATABASE 'x' USER 'SYSDBA'"));
        assertNull(ObjectKey.of("INSERT INTO T ( ID ) VALUES ( 1 )"));
        assertNull(ObjectKey.of("COMMIT WORK"));
    }

    @Test
    void allowedDiffsAcceptByObjectOrByKindAndReportUnusedEntries(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("allowed-diffs");
        Files.writeString(file, "# comentário\n\nDOMAIN D1 | motivo um\nGENERATOR * | motivo dois\nDOMAIN D9 | velho\n");
        AllowedDiffs allowed = AllowedDiffs.load(file);
        List<ScriptComparator.Difference> differences = ScriptComparator.compare(
                "CREATE DOMAIN D1 AS INTEGER; CREATE DOMAIN D2 AS DATE; CREATE GENERATOR G1;",
                "CREATE DOMAIN D1 AS BIGINT; CREATE DOMAIN D2 AS DATE;", ALL, allowed);
        assertTrue(differences.isEmpty());
        assertEquals(Set.of("DOMAIN D9"), allowed.unused());
    }

    @Test
    void allowedDiffsEntryWithoutReasonIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("allowed-diffs");
        Files.writeString(file, "DOMAIN D1\n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> AllowedDiffs.load(file));
        assertTrue(error.getMessage().contains("motivo"));
    }
}
