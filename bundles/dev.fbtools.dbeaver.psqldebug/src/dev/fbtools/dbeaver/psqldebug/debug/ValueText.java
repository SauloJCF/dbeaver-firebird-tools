package dev.fbtools.dbeaver.psqldebug.debug;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Texto de um valor como o Jaybird o devolveu, para a view Variables e o console. Fora o binário, é um texto que o
 * servidor converte de volta no mesmo valor ({@code CAST}), e serve de ponto de partida para editar a variável.
 */
final class ValueText {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xxx");
    /** O Firebird guarda frações de segundo em décimos de milésimo. */
    private static final int NANOS_PER_FRACTION = 100_000;

    private ValueText() {
    }

    /**
     * {@code NULL}; {@code NUMERIC} sem notação científica; {@code BOOLEAN} como {@code TRUE}/{@code FALSE};
     * {@code TIMESTAMP} como {@code 2026-10-17 10:20:30.123}, sem a fração quando ela é zero; binário em
     * hexadecimal; o resto, como o Java o escreve ({@code BLOB SUB_TYPE TEXT} já vem como texto).
     */
    static String of(Object value) {
        return switch (value) {
            case null -> "NULL";
            case byte[] bytes -> "0x" + HexFormat.of().withUpperCase().formatHex(bytes);
            case BigDecimal decimal -> decimal.toPlainString();
            case Boolean bool -> bool ? "TRUE" : "FALSE";
            case java.sql.Timestamp timestamp -> timestamp(timestamp.toLocalDateTime());
            case LocalDateTime timestamp -> timestamp(timestamp);
            case OffsetDateTime timestamp -> timestamp(timestamp.toLocalDateTime()) + " "
                    + timestamp.format(OFFSET);
            case ZonedDateTime timestamp -> timestamp(timestamp.toLocalDateTime()) + " " + timestamp.getZone();
            default -> value.toString();
        };
    }

    private static String timestamp(LocalDateTime timestamp) {
        String text = timestamp.format(TIMESTAMP);
        int fraction = timestamp.getNano() / NANOS_PER_FRACTION;
        if (fraction == 0) {
            return text;
        }
        String digits = String.format("%04d", fraction);
        return text + "." + digits.replaceFirst("0+$", "");
    }
}
