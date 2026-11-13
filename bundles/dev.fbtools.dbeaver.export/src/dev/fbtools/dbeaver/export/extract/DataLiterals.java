package dev.fbtools.dbeaver.export.extract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Valores como literais SQL do Script de dados, conforme as {@link DataOptions}. Os números saem sem zeros à direita
 * nem notação científica (menos os muito pequenos ou grandes), como o IBExpert escreve.
 */
final class DataLiterals {

    private static final BigDecimal SMALLEST_PLAIN = new BigDecimal("0.0001");
    private static final BigDecimal LARGEST_PLAIN = new BigDecimal("1E15");

    private final DataOptions options;
    private final DateTimeFormatter dateFormat;
    private final DateTimeFormatter dateTimeFormat;

    DataLiterals(DataOptions options) {
        String formatError = DataOptions.checkFormats(options.dateFormat(), options.dateTimeFormat());
        if (formatError != null) {
            throw new IllegalArgumentException(formatError);
        }
        this.options = options;
        this.dateFormat = options.dateFormat() == null ? null : DateTimeFormatter.ofPattern(options.dateFormat());
        this.dateTimeFormat =
            options.dateTimeFormat() == null ? null : DateTimeFormatter.ofPattern(options.dateTimeFormat());
    }

    /** Texto entre apóstrofos; as opções de espaços finais e caracteres de controle valem aqui. */
    String text(String value) {
        String text = value;
        if (options.stripControlCharacters()) {
            text = text.codePoints().filter(c -> c >= 0x20 && c != 0x7f)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        }
        if (options.trimTrailingSpaces()) {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == ' ') {
                end--;
            }
            text = text.substring(0, end);
        }
        return "'" + text.replace("'", "''") + "'";
    }

    /** Bytes como {@code x'…'}, para BLOB binário e colunas {@code OCTETS}. */
    String hex(byte[] value) {
        StringBuilder sql = new StringBuilder(value.length * 2 + 3).append("x'");
        for (byte b : value) {
            sql.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sql.append('\'').toString();
    }

    /** Inteiro ou numérico exato: o valor sem zeros à direita ({@code 5000.00} sai {@code 5000}). */
    String exact(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** {@code DECFLOAT}: preciso e aceito com valores especiais, por isso sai como cast de texto. */
    String decfloat(String value, int digits) {
        return "CAST('" + value.strip() + "' AS DECFLOAT(" + digits + "))";
    }

    String floating(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? special(Float.toString(value))
                : floating(Float.toString(value));
    }

    String floating(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? special(Double.toString(value))
                : floating(Double.toString(value));
    }

    private static String special(String javaText) {
        return "CAST('" + javaText + "' AS DOUBLE PRECISION)";
    }

    private static String floating(String javaText) {
        BigDecimal value = new BigDecimal(javaText);
        if (value.signum() == 0) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        BigDecimal magnitude = stripped.abs();
        boolean plain = magnitude.compareTo(SMALLEST_PLAIN) >= 0 && magnitude.compareTo(LARGEST_PLAIN) < 0;
        return plain ? stripped.toPlainString() : stripped.toString();
    }

    String bool(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    String date(LocalDate value) {
        return prefixed("DATE", dateFormat == null ? value.toString() : dateFormat.format(value));
    }

    String time(LocalTime value) {
        return prefixed("TIME", clock(value));
    }

    String timestamp(LocalDateTime value) {
        return prefixed("TIMESTAMP", dateTimeFormat == null ? value.toLocalDate() + " " + clock(value.toLocalTime())
                : dateTimeFormat.format(value));
    }

    String timeWithZone(OffsetTime value) {
        return prefixed("TIME", clock(value.toLocalTime()) + " " + zone(value.getOffset()));
    }

    String timestampWithZone(ZonedDateTime value) {
        return prefixed("TIMESTAMP", value.toLocalDate() + " " + clock(value.toLocalTime()) + " " + zone(value.getZone()));
    }

    private String prefixed(String keyword, String text) {
        return (options.ansiPrefix() ? keyword + " " : "") + "'" + text + "'";
    }

    /** {@code HH:mm:ss} e, se houver, as frações até a casa de 1/10000 s que o Firebird guarda. */
    private static String clock(LocalTime value) {
        StringBuilder text = new StringBuilder(String.format("%02d:%02d:%02d", value.getHour(), value.getMinute(),
                value.getSecond()));
        int tenthsOfMillisecond = value.getNano() / 100_000;
        if (tenthsOfMillisecond != 0) {
            String fraction = String.format("%04d", tenthsOfMillisecond);
            int end = fraction.length();
            while (fraction.charAt(end - 1) == '0') {
                end--;
            }
            text.append('.').append(fraction, 0, end);
        }
        return text.toString();
    }

    private static String zone(ZoneId zone) {
        if (zone instanceof ZoneOffset offset) {
            return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
        }
        return zone.getId();
    }
}
