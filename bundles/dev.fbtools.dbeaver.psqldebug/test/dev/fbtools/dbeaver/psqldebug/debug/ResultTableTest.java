package dev.fbtools.dbeaver.psqldebug.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Linhas do {@code SUSPEND} no fim da sessão, no console (issue 18). */
class ResultTableTest {

    @Test
    void rowsAreNumberedInEmissionOrderAndAlignedByTheWidestValue() {
        String table = ResultTable.of(List.of("ID", "DESCRICAO", "FALTA"), List.of(
                Arrays.asList(4, "Webcam HD", new BigDecimal("15.00")),
                Arrays.asList(12, null, new BigDecimal("2.50"))));

        assertEquals("""
                   #  ID  DESCRICAO  FALTA
                   1  4   Webcam HD  15.00
                   2  12  NULL       2.50
                """, table);
    }

    @Test
    void longOrMultilineValuesStayOnOneShortLine() {
        String table = ResultTable.of(List.of("TXT"), List.of(List.of("a\nb"), List.of("x".repeat(100))));

        List<String> lines = table.lines().toList();
        assertEquals("   1  a b", lines.get(1));
        assertEquals(ResultTable.MAX_CELL, lines.get(2).substring("   2  ".length()).length());
        assertTrue(lines.get(2).endsWith("…"), lines.get(2));
    }
}
