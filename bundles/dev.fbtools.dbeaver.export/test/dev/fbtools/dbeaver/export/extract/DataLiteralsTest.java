package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

/** Issue 08: o formato dos literais do Script de dados, sem banco. */
class DataLiteralsTest {

    private static final DataLiterals DEFAULT = new DataLiterals(DataOptions.DEFAULT);

    @Test
    void numbersLoseTrailingZerosAndKeepTheirValue() {
        assertEquals("5000", DEFAULT.exact(new BigDecimal("5000.00")));
        assertEquals("12000.5", DEFAULT.exact(new BigDecimal("12000.50")));
        assertEquals("0", DEFAULT.exact(new BigDecimal("0.00")));
        assertEquals("-0.085", DEFAULT.exact(new BigDecimal("-0.0850")));
        assertEquals("100", DEFAULT.exact(new BigDecimal("1E+2")));
        assertEquals("170141183460469231731687303715884105727",
                DEFAULT.exact(new BigDecimal("170141183460469231731687303715884105727")));
    }

    @Test
    void floatingPointNumbersUseTheShortestTextThatReadsBackTheSameValue() {
        assertEquals("0.085", DEFAULT.floating(0.085f));
        assertEquals("1.5", DEFAULT.floating(1.5d));
        assertEquals("-325000", DEFAULT.floating(-325000f));
        assertEquals("1E-10", DEFAULT.floating(1e-10d));
        assertEquals("1E+20", DEFAULT.floating(1e20d));
        assertEquals("0", DEFAULT.floating(-0.0d));
        assertEquals("CAST('NaN' AS DOUBLE PRECISION)", DEFAULT.floating(Double.NaN));
        assertEquals("CAST('Infinity' AS DOUBLE PRECISION)", DEFAULT.floating(Double.POSITIVE_INFINITY));
    }

    @Test
    void decfloatGoesThroughATextCastSoNothingIsRounded() {
        assertEquals("CAST('1.234567890123456789012345678901234E+10' AS DECFLOAT(34))",
                DEFAULT.decfloat("1.234567890123456789012345678901234E+10", 34));
        assertEquals("CAST('1.5' AS DECFLOAT(16))", DEFAULT.decfloat(" 1.5 ", 16));
    }

    @Test
    void textDoublesApostrophesAndKeepsLineBreaksByDefault() {
        assertEquals("'Maria D''Ávila'", DEFAULT.text("Maria D'Ávila"));
        assertEquals("'a\nb'", DEFAULT.text("a\nb"));
        assertEquals("'abc  '", DEFAULT.text("abc  "));
        assertEquals("''", DEFAULT.text(""));
    }

    @Test
    void textOptionsTrimTrailingSpacesAndStripControlCharacters() {
        DataLiterals trim = new DataLiterals(DataOptions.DEFAULT.withTexts(true, false));
        assertEquals("'abc'", trim.text("abc   "));
        assertEquals("' a b'", trim.text(" a b  "));
        assertEquals("''", trim.text("   "));
        DataLiterals strip = new DataLiterals(DataOptions.DEFAULT.withTexts(false, true));
        assertEquals("'linha1linha2 x'", strip.text("linha1\r\nlinha2\t x\u007f"));
    }

    @Test
    void binaryValuesBecomeHexLiterals() {
        assertEquals("x'00ff10'", DEFAULT.hex(new byte[] {0, (byte) 0xff, 0x10}));
        assertEquals("x''", DEFAULT.hex(new byte[0]));
    }

    @Test
    void datesAndTimesKeepTheFractionsOfASecondTheServerStores() {
        assertEquals("'2024-03-10'", DEFAULT.date(LocalDate.of(2024, 3, 10)));
        assertEquals("'23:59:59.9999'", DEFAULT.time(LocalTime.of(23, 59, 59, 999_900_000)));
        assertEquals("'00:00:00'", DEFAULT.time(LocalTime.MIDNIGHT));
        assertEquals("'10:00:00.5'", DEFAULT.time(LocalTime.of(10, 0, 0, 500_000_000)));
        assertEquals("'2099-12-31 23:59:59.9999'",
                DEFAULT.timestamp(LocalDateTime.of(2099, 12, 31, 23, 59, 59, 999_900_000)));
        assertEquals("'1900-01-01 00:00:00'", DEFAULT.timestamp(LocalDateTime.of(1900, 1, 1, 0, 0)));
    }

    @Test
    void zonesAreWrittenAsTheServerReadsThem() {
        assertEquals("'10:00:00 -03:00'", DEFAULT.timeWithZone(OffsetTime.of(10, 0, 0, 0, ZoneOffset.ofHours(-3))));
        assertEquals("'10:00:00 +00:00'", DEFAULT.timeWithZone(OffsetTime.of(10, 0, 0, 0, ZoneOffset.UTC)));
        assertEquals("'2024-01-15 10:30:00 America/Sao_Paulo'", DEFAULT.timestampWithZone(
                ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneId.of("America/Sao_Paulo"))));
        assertEquals("'2024-01-15 10:30:00 +05:30'", DEFAULT.timestampWithZone(
                ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))));
    }

    @Test
    void ansiPrefixAndCustomFormats() {
        DataLiterals ansi = new DataLiterals(DataOptions.DEFAULT.withAnsiPrefix(true));
        assertEquals("DATE '2024-03-10'", ansi.date(LocalDate.of(2024, 3, 10)));
        assertEquals("TIME '10:00:00'", ansi.time(LocalTime.of(10, 0)));
        assertEquals("TIMESTAMP '2024-03-10 08:00:00'", ansi.timestamp(LocalDateTime.of(2024, 3, 10, 8, 0)));
        DataLiterals custom = new DataLiterals(DataOptions.DEFAULT.withFormats("dd.MM.yyyy", "dd.MM.yyyy HH:mm"));
        assertEquals("'10.03.2024'", custom.date(LocalDate.of(2024, 3, 10)));
        assertEquals("'10.03.2024 08:05'", custom.timestamp(LocalDateTime.of(2024, 3, 10, 8, 5, 30)));
    }

    @Test
    void formatsAreCheckedAgainstTheTypeTheyFormat() {
        assertEquals(null, DataOptions.checkFormats(null, null));
        assertEquals(null, DataOptions.checkFormats("", "  "));
        assertEquals(null, DataOptions.checkFormats("dd.MM.yyyy", "dd.MM.yyyy HH:mm:ss"));
        // Compila, mas uma data não tem hora.
        assertTrue(DataOptions.checkFormats("dd/MM/yyyy HH:mm", null).contains("data"));
        assertTrue(DataOptions.checkFormats(null, "yyyy-MM-dd'T'HH:mm:ss.SSSS XXX").contains("hora"));
        assertTrue(DataOptions.checkFormats("yyyy-MM-dd'", null) != null);
    }

    @Test
    void invalidFormatsFailBeforeAnythingIsWritten() {
        assertThrows(IllegalArgumentException.class,
                () -> new DataLiterals(DataOptions.DEFAULT.withFormats("dd/MM/yyyy HH:mm", null)));
    }

    @Test
    void booleans() {
        assertEquals("TRUE", DEFAULT.bool(true));
        assertEquals("FALSE", DEFAULT.bool(false));
    }
}
