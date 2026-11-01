package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** Leitura de colunas de {@code RDB$*}: o CHAR do catálogo vem com espaços à direita, e os vazios são {@code null}. */
final class Rdb {

    private Rdb() {
    }

    static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static String trimmed(String value) {
        return value == null ? null : value.strip();
    }

    /** Texto de fonte (default, check, descrição) sem espaços nas pontas; {@code null} se vazio. */
    static String source(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * As dimensões das Colunas array por campo ({@code RDB$FIELD_DIMENSIONS}), no formato da sintaxe: {@code [1:5]},
     * {@code [1:5,0:2]}. Campos sem dimensão não aparecem.
     */
    static Map<String, String> dimensions(Connection connection) throws SQLException {
        Map<String, String> dimensions = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT RDB$FIELD_NAME, RDB$LOWER_BOUND, RDB$UPPER_BOUND "
                        + "FROM RDB$FIELD_DIMENSIONS ORDER BY RDB$FIELD_NAME, RDB$DIMENSION")) {
            while (rs.next()) {
                String bounds = rs.getInt(2) + ":" + rs.getInt(3);
                dimensions.merge(rs.getString(1).strip(), bounds, (all, next) -> all + "," + next);
            }
        }
        dimensions.replaceAll((field, bounds) -> "[" + bounds + "]");
        return dimensions;
    }
}
