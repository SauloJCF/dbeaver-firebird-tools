package dev.fbtools.dbeaver.psqldebug.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class OutputComparisonTest {

    @Test
    void divergenceShowsNativeAndInterpretedValuePerOutput() {
        AssertionError error = assertThrows(AssertionError.class, () -> OutputComparison.assertSame("P(1)",
                List.of("TOTAL", "NOME", "IGUAL"),
                Arrays.asList(new BigDecimal("12.3400"), "ab  ", 1),
                List.of("TOTAL", "NOME", "IGUAL"),
                Arrays.asList(new BigDecimal("12.3500"), null, 1)));

        assertEquals("""
                P(1): 2 saída(s) divergem entre a execução nativa e o Interpretador
                  TOTAL: nativo=12.3400 (BigDecimal) × interpretado=12.3500 (BigDecimal)
                  NOME: nativo='ab  ' (String) × interpretado=null""", error.getMessage());
    }

    @Test
    void differentScaleIsADivergence() {
        AssertionError error = assertThrows(AssertionError.class, () -> OutputComparison.assertSame("P()",
                List.of("V"), List.of(new BigDecimal("1.50")), List.of("V"), List.of(new BigDecimal("1.5"))));

        assertEquals("""
                P(): 1 saída(s) divergem entre a execução nativa e o Interpretador
                  V: nativo=1.50 (BigDecimal) × interpretado=1.5 (BigDecimal)""", error.getMessage());
    }

    @Test
    void differentOutputNamesAreReported() {
        AssertionError error = assertThrows(AssertionError.class, () -> OutputComparison.assertSame("P()",
                List.of("A", "B"), List.of(1, 2), List.of("A"), List.of(1)));

        assertEquals("P(): parâmetros de saída diferentes: nativo=[A, B] × interpretado=[A]", error.getMessage());
    }

    @Test
    void equalOutputsPass() {
        assertDoesNotThrow(() -> OutputComparison.assertSame("P()", List.of("A", "B"),
                Arrays.asList(null, new byte[] {1, 2}), List.of("A", "B"), Arrays.asList(null, new byte[] {1, 2})));
    }
}
