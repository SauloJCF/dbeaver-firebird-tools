package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Tipo SQL de uma coluna de cursor, a partir da descrição que o servidor manda. Serve para reenviar o valor de um
 * campo ({@code cursor.campo}) como {@code CAST(? AS <tipo>)} com o tipo declarado da coluna, inclusive quando o
 * valor é {@code NULL}.
 */
final class ColumnTypes {

    /** Maior {@code VARCHAR} em UTF8: 32765 bytes, até 4 por caractere. */
    private static final int MAX_UTF8_CHARS = 8191;

    private ColumnTypes() {
    }

    static String sqlType(ResultSetMetaData metaData, int column) throws SQLException {
        int type = metaData.getColumnType(column);
        // O conjunto de caracteres não vem na descrição; o texto chega ao Jaybird em UTF8.
        boolean text = type == Types.CHAR || type == Types.VARCHAR;
        return name(metaData, column)
                + (text && metaData.getPrecision(column) <= MAX_UTF8_CHARS ? " CHARACTER SET UTF8" : "");
    }

    /** Tipo da coluna para mostrar: como o de {@link #sqlType}, sem o conjunto de caracteres. */
    static String name(ResultSetMetaData metaData, int column) throws SQLException {
        int length = metaData.getPrecision(column);
        return switch (metaData.getColumnType(column)) {
            case Types.CHAR, Types.VARCHAR -> metaData.getColumnTypeName(column) + "(" + length + ")";
            case Types.BINARY -> "BINARY(" + length + ")";
            case Types.VARBINARY -> "VARBINARY(" + length + ")";
            case Types.NUMERIC, Types.DECIMAL -> "INT128".equals(metaData.getColumnTypeName(column)) ? "INT128"
                    : metaData.getColumnTypeName(column) + "(" + length + "," + metaData.getScale(column) + ")";
            case 2015 /* JaybirdTypeCodes.DECFLOAT */ -> "DECFLOAT(" + length + ")";
            default -> metaData.getColumnTypeName(column);
        };
    }
}
