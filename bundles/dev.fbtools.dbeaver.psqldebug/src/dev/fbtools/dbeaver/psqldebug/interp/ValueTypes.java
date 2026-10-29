package dev.fbtools.dbeaver.psqldebug.interp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;

/**
 * Tipo SQL que representa sem perda um valor que o Jaybird devolveu. Serve para reenviar o valor ao servidor como
 * {@code CAST(? AS <tipo>)} e deixar o próprio servidor convertê-lo para o tipo de uma variável, como numa
 * atribuição.
 */
final class ValueTypes {

    /** Maior {@code VARCHAR} em UTF8: 32765 bytes, até 4 por caractere. */
    private static final int MAX_UTF8_VARCHAR = 8191;
    private static final int MAX_VARBINARY = 32765;

    private ValueTypes() {
    }

    /** @param value valor não nulo */
    static String sqlType(Object value) {
        return switch (value) {
            case String text -> text.length() <= MAX_UTF8_VARCHAR
                    ? "VARCHAR(" + Math.max(1, text.length()) + ") CHARACTER SET UTF8"
                    : "BLOB SUB_TYPE TEXT CHARACTER SET UTF8";
            case byte[] bytes -> bytes.length <= MAX_VARBINARY ? "VARBINARY(" + Math.max(1, bytes.length) + ")"
                    : "BLOB SUB_TYPE BINARY";
            case Short s -> "SMALLINT";
            case Integer i -> "INTEGER";
            case Long l -> "BIGINT";
            case BigInteger i -> "INT128";
            case BigDecimal decimal -> decimal(decimal);
            case Float f -> "FLOAT";
            case Double d -> "DOUBLE PRECISION";
            case Boolean b -> "BOOLEAN";
            case java.sql.Timestamp t -> "TIMESTAMP";
            case java.sql.Date d -> "DATE";
            case java.sql.Time t -> "TIME";
            case LocalDateTime t -> "TIMESTAMP";
            case LocalDate d -> "DATE";
            case LocalTime t -> "TIME";
            case OffsetDateTime t -> "TIMESTAMP WITH TIME ZONE";
            case ZonedDateTime t -> "TIMESTAMP WITH TIME ZONE";
            case OffsetTime t -> "TIME WITH TIME ZONE";
            default -> throw new PsqlExecutionException(
                    "Valor do servidor de tipo não suportado: " + value.getClass().getName());
        };
    }

    /** {@code NUMERIC} com a escala do valor; o que não cabe num {@code NUMERIC} veio de um {@code DECFLOAT}. */
    private static String decimal(BigDecimal value) {
        int scale = value.scale();
        int digits = Math.max(value.precision(), scale);
        if (scale < 0 || digits > 38) {
            return "DECFLOAT(34)";
        }
        return "NUMERIC(" + (digits <= 18 ? 18 : 38) + "," + scale + ")";
    }
}
