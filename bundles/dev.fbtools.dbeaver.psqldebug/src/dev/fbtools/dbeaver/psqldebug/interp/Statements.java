package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** {@link PreparedStatement} com os parâmetros já ligados, fechado se a ligação falhar. */
final class Statements {

    private Statements() {
    }

    static PreparedStatement prepare(Connection connection, String sql, List<?> parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
        return statement;
    }
}
