package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.fbtools.dbeaver.psqldebug.psql.ast.ErrorCondition;

/**
 * Erro que um handler {@code WHEN} vê, com {@code GDSCODE}, {@code SQLCODE} e {@code SQLSTATE} calculados como o
 * motor calcula a partir do status vector: o {@code GDSCODE} é o primeiro código; o {@code SQLCODE} vem do
 * argumento de {@code isc_sqlerr} se houver, senão da tabela do motor para o primeiro código.
 *
 * @param error o erro, para relançar com {@code EXCEPTION;}; {@code null} em {@link #NONE}
 * @param exception nome da exceção de usuário ({@code isc_except}), como nos metadados; ou {@code null}
 */
record ErrorStatus(SQLException error, int gdsCode, int sqlCode, String sqlState, String exception) {

    /**
     * Sem erro: fora de handler, depois que um handler termina, ou num {@code WHEN ANY} que o motor escolheu depois
     * de outro handler já ter casado (ver {@code Interpreter}).
     */
    static final ErrorStatus NONE = new ErrorStatus(null, 0, 0, "00000", null);

    private static final Pattern SQLERR_MESSAGE = Pattern.compile("SQL error code = (-?\\d+)");

    /**
     * O Jaybird põe o status vector inteiro como causa: uma {@link SQLException} por código, ligadas por
     * {@link SQLException#getNextException()}. Erros montados no cliente ({@link ServerErrors}) têm um código só.
     */
    static ErrorStatus of(SQLException error) {
        List<SQLException> items = new ArrayList<>();
        if (error.getCause() instanceof SQLException status && status.getErrorCode() != 0) {
            for (SQLException item = status; item != null; item = item.getNextException()) {
                items.add(item);
            }
        } else {
            items.add(error);
        }
        int gdsCode = items.get(0).getErrorCode();
        int sqlCode = GdsCodes.sqlCode(gdsCode);
        for (SQLException item : items) {
            Matcher matcher = SQLERR_MESSAGE.matcher(String.valueOf(item.getMessage()));
            if (item.getErrorCode() == GdsCodes.SQLERR && matcher.find()) {
                sqlCode = Integer.parseInt(matcher.group(1));
                break;
            }
        }
        String sqlState = error.getSQLState() != null ? error.getSQLState() : GdsCodes.sqlState(gdsCode);
        String exception = gdsCode == GdsCodes.EXCEPT && items.size() > 1
                && items.get(1).getErrorCode() == GdsCodes.RANDOM ? items.get(1).getMessage() : null;
        return new ErrorStatus(error, gdsCode, sqlCode, sqlState, exception);
    }

    /** Se um {@code WHEN} com esta condição trata o erro. {@link #NONE} só é tratado por {@code ANY}. */
    boolean matches(ErrorCondition condition) {
        if (error == null) {
            return condition instanceof ErrorCondition.AnyError;
        }
        return switch (condition) {
            case ErrorCondition.UserException user -> gdsCode == GdsCodes.EXCEPT && user.name().equals(exception);
            case ErrorCondition.SqlCode code -> sqlCode == code.code();
            case ErrorCondition.GdsCode code -> gdsCode == GdsCodes.code(code.name());
            case ErrorCondition.SqlState state -> state.state().equals(sqlState);
            case ErrorCondition.AnyError any -> true;
        };
    }
}
