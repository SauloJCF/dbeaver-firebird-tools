package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Tipos nativos na Sessão de debug (issue 24). Parte de uma conexão sem ajuste nenhum, que num servidor com
 * {@code DataTypeCompatibility = 2.5} devolve {@code BOOLEAN} e {@code DECFLOAT} com outro tipo.
 */
class NativeTypesTest {

    @Test
    void booleanAndDecfloatKeepTheirNativeTypeAfterBind() throws Exception {
        try (Connection connection = TestDatabase.get().connectAsIs()) {
            NativeTypes.bind(connection);
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT TRUE, CAST(1000 AS DECFLOAT(16)) FROM RDB$DATABASE")) {
                assertEquals(Types.BOOLEAN, rows.getMetaData().getColumnType(1));
                assertEquals(true, rows.next());
                assertEquals(Boolean.TRUE, rows.getObject(1));
                assertEquals(new BigDecimal("1000"), rows.getObject(2));
            }
        }
    }

    @Test
    void afterBindABooleanColumnWorksAsACondition() throws Exception {
        try (Connection connection = TestDatabase.get().connectAsIs()) {
            NativeTypes.bind(connection);
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT 1 FROM RDB$DATABASE D JOIN (SELECT TRUE AS ATIVO FROM RDB$DATABASE) C ON C.ATIVO")) {
                assertEquals(true, rows.next());
            }
        }
    }

    @Test
    void aFailingBindIsReportedNotSwallowed() throws Exception {
        Connection connection = TestDatabase.get().connectAsIs();
        connection.close();
        try {
            NativeTypes.bind(connection);
            throw new AssertionError("esperava SQLException numa conexão fechada");
        } catch (SQLException expected) {
            // o launch é abortado com esta mensagem
        }
    }
}
