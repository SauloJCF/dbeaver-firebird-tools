package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import dev.fbtools.dbeaver.psqldebug.psql.Identifiers;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Raise;

/**
 * {@code EXCEPTION nome ...}: a exceção de usuário é levantada pelo próprio servidor, num {@code EXECUTE BLOCK},
 * para que o erro tenha o número da exceção, o código, o SQLSTATE e a mensagem do motor, inclusive a formatação
 * dos {@code @1}, {@code @2}... do {@code USING}. A mensagem e os argumentos são avaliados antes, com as
 * variáveis da Rotina.
 */
final class ExceptionRaiser {

    private final Connection connection;
    private final ExpressionEvaluator evaluator;

    ExceptionRaiser(Connection connection, ExpressionEvaluator evaluator) {
        this.connection = connection;
        this.evaluator = evaluator;
    }

    /** @return o erro que o servidor levantou, para ser lançado no ponto do {@code EXCEPTION} */
    SQLException raise(Raise raise, Frame frame) throws SQLException {
        List<String> declarations = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        StringBuilder statement = new StringBuilder("EXCEPTION ").append(Identifiers.quote(raise.exception()));
        if (raise.message() != null) {
            statement.append(' ').append(input(evaluator.value(raise.message(), frame), declarations, values));
        } else if (!raise.arguments().isEmpty()) {
            List<String> arguments = new ArrayList<>();
            for (Expression argument : raise.arguments()) {
                arguments.add(input(evaluator.value(argument, frame), declarations, values));
            }
            statement.append(" USING (").append(String.join(", ", arguments)).append(')');
        }
        String block = "EXECUTE BLOCK" + (declarations.isEmpty() ? "" : " (" + String.join(", ", declarations) + ")")
                + " AS BEGIN " + statement + "; END";
        try (PreparedStatement prepared = Statements.prepare(connection, block, values)) {
            prepared.execute();
        } catch (SQLException e) {
            return e;
        }
        throw new PsqlExecutionException("O servidor não levantou a exceção " + raise.exception());
    }

    /** Parâmetro de entrada do bloco com o valor já avaliado, ou {@code NULL}. */
    private static String input(Object value, List<String> declarations, List<Object> values) {
        if (value == null) {
            return "NULL";
        }
        String name = "P" + values.size();
        declarations.add(name + " " + ValueTypes.sqlType(value) + " = ?");
        values.add(value);
        return ":" + name;
    }
}
