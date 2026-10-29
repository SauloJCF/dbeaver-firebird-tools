package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;

/**
 * Erros que o motor levantaria e que o Interpretador detecta no cliente, com o código, o SQLSTATE e a mensagem do
 * motor. Um handler {@code WHEN} os trata como qualquer erro do servidor.
 */
final class ServerErrors {

    private ServerErrors() {
    }

    /** {@code isc_sing_select_err}: {@code SELECT … INTO}/{@code RETURNING … INTO} com mais de uma linha. */
    static SQLException multipleRowsInSingletonSelect() {
        return engineError("sing_select_err", "multiple rows in singleton select");
    }

    /** {@code isc_not_valid_for_var}: {@code NULL} em variável {@code NOT NULL}. */
    static SQLException nullInNotNullVariable(String variable) {
        return engineError("not_valid_for_var",
                "validation error for variable " + variable + ", value \"*** null ***\"");
    }

    /**
     * {@code isc_not_valid_for}: {@code NULL} devolvido por function com retorno {@code NOT NULL}, no
     * {@code RETURN} ou no fim. (Na mesma conexão, depois que um {@code WHEN} tratou esse erro uma vez, o motor
     * passa a dizer "output parameter number 1" no erro do fim; a Sessão de debug começa em conexão nova.)
     */
    static SQLException nullReturned() {
        return engineError("not_valid_for", "validation error for variable number 1, value \"*** null ***\"");
    }

    /** {@code isc_cursor_not_open}: {@code FETCH} ou campo de um cursor fechado. */
    static SQLException cursorNotOpen() {
        return engineError("cursor_not_open", "Cursor is not open");
    }

    /** {@code isc_cursor_not_positioned}: campo de um cursor sem linha atual. */
    static SQLException cursorNotPositioned(String cursor) {
        return engineError("cursor_not_positioned", "Cursor " + cursor + " is not positioned in a valid record");
    }

    private static SQLException engineError(String name, String message) {
        int code = GdsCodes.code(name);
        return new SQLException(message, GdsCodes.sqlState(code), code);
    }
}
