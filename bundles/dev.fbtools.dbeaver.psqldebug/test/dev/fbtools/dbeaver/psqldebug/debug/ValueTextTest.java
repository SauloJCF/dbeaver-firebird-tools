package dev.fbtools.dbeaver.psqldebug.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Valores na view Variables (issue 15), como o Jaybird os devolve do Firebird: legíveis, e um texto que o servidor
 * converte de volta no mesmo valor quando a variável é editada.
 */
class ValueTextTest {

    /** Valor do servidor, o texto esperado e o tipo para a volta. */
    private record Case(String expression, String text, String type) {
    }

    private static final List<Case> CASES = List.of(
            new Case("CAST(12.5 AS NUMERIC(10,2))", "12.50", "NUMERIC(10,2)"),
            new Case("CAST(-0.0001 AS NUMERIC(18,4))", "-0.0001", "NUMERIC(18,4)"),
            new Case("CAST(1000 AS DECFLOAT(16))", "1000", "DECFLOAT(16)"),
            new Case("DATE '2026-10-17'", "2026-10-17", "DATE"),
            new Case("TIMESTAMP '2026-10-17 10:20:30'", "2026-10-17 10:20:30", "TIMESTAMP"),
            new Case("TIMESTAMP '2026-10-17 10:20:30.1230'", "2026-10-17 10:20:30.123", "TIMESTAMP"),
            new Case("TIME '10:20:30'", "10:20:30", "TIME"),
            new Case("CAST('linha 1' || ASCII_CHAR(10) || 'linha 2' AS BLOB SUB_TYPE TEXT)", "linha 1\nlinha 2",
                    "BLOB SUB_TYPE TEXT"),
            new Case("CAST('ab' AS CHAR(4))", "ab  ", "CHAR(4)"),
            new Case("TRUE", "TRUE", "BOOLEAN"),
            new Case("CAST(NULL AS INTEGER)", "NULL", "INTEGER"),
            new Case("CAST(X'CAFE' AS VARBINARY(2))", "0xCAFE", null));

    @Test
    void valuesAreShownReadablyAndConvertBackToTheSameValue() throws Exception {
        List<String> shown = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        try (Connection connection = TestDatabase.get().connect()) {
            for (Case item : CASES) {
                Object value = select(connection, "SELECT " + item.expression() + " FROM RDB$DATABASE", null);
                shown.add(ValueText.of(value));
                expected.add(item.text());
                if (item.type() != null && value != null) {
                    // Como na edição: texto em VARCHAR, convertido pelo CAST do servidor.
                    Object back = select(connection, "SELECT CAST(CAST(? AS VARCHAR(100) CHARACTER SET UTF8) AS "
                            + item.type() + ") FROM RDB$DATABASE", ValueText.of(value));
                    assertTrue(Objects.deepEquals(value, back), item.expression() + " voltou como " + back);
                }
            }
        }
        assertEquals(expected, shown);
    }

    private static Object select(Connection connection, String sql, Object parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getObject(1);
            }
        }
    }
}
