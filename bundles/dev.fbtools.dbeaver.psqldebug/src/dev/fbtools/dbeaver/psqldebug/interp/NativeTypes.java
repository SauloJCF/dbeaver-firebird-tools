package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Fixa os tipos nativos do Firebird 5 na conexão de uma Sessão de debug. Um servidor com
 * {@code DataTypeCompatibility = 2.5} devolve {@code BOOLEAN} como {@code CHAR(5)}, {@code DECFLOAT} como
 * {@code DOUBLE PRECISION} e {@code INT128} e os tipos com fuso horário como tipos mais antigos, o que muda o texto
 * das Variables e o resultado da Avaliação de expressão. {@code SET BIND} vale só para a sessão e não mexe na conexão
 * do usuário no DBeaver.
 */
public final class NativeTypes {

    private static final List<String> STATEMENTS = List.of(
            "SET BIND OF BOOLEAN TO NATIVE",
            "SET BIND OF DECFLOAT TO NATIVE",
            "SET BIND OF INT128 TO NATIVE",
            "SET BIND OF TIME ZONE TO NATIVE");

    private NativeTypes() {
    }

    /**
     * Aplica os binds antes de qualquer transação da Sessão.
     *
     * @throws SQLException o erro do servidor, por exemplo um servidor sem {@code SET BIND}; o launch é abortado
     */
    public static void bind(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : STATEMENTS) {
                statement.execute(sql);
            }
        }
    }
}
