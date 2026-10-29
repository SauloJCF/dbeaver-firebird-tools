package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.fbtools.dbeaver.psqldebug.psql.DynamicSql;
import dev.fbtools.dbeaver.psqldebug.psql.ast.DmlStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ExecuteStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;

/**
 * Executa Fatias DML e {@code EXECUTE STATEMENT} local no servidor, na transação da Sessão de debug, abre os
 * cursores e mantém o {@code ROW_COUNT} do {@link Frame}.
 */
final class DmlExecutor {

    private final Connection connection;
    private final ExpressionEvaluator evaluator;

    DmlExecutor(Connection connection, ExpressionEvaluator evaluator) {
        this.connection = connection;
        this.evaluator = evaluator;
    }

    /**
     * O {@code ROW_COUNT} passa a ser o número de linhas afetadas ou, com {@code INTO}, lidas.
     *
     * @return a linha para os alvos do {@code INTO}, já convertida para os tipos deles; {@code null} se não há
     *         {@code INTO} ou se não veio linha, caso em que o motor deixa os alvos como estavam
     */
    List<Object> execute(DmlStatement dml, Frame frame) throws SQLException {
        BoundSql bound = BoundSql.bind(dml.sql(), frame);
        if (dml.kind() == DmlStatement.Kind.SELECT) {
            List<Object> row = singleRow(bound.sql(), bound.parameters(), dml.into().size());
            frame.rowsFetched(row == null ? 0 : 1);
            return row == null ? null : assignable(row, dml.into(), frame);
        }
        SavepointStack savepoints = frame.savepoints();
        savepoints.beforeChange();
        long rows;
        List<Object> row = null;
        if (dml.into().isEmpty() && dml.kind() == DmlStatement.Kind.MERGE) {
            rows = mergedRows(bound);
        } else if (dml.into().isEmpty()) {
            try (PreparedStatement statement = Statements.prepare(connection, bound.sql(), bound.parameters())) {
                rows = statement.executeUpdate();
            }
        } else {
            // No RETURNING, o DML já aconteceu quando a segunda linha aparece; o motor desfaz o statement inteiro.
            row = Savepoints.undoneOnError(connection,
                    () -> singleRow(bound.sql(), bound.parameters(), dml.into().size()));
            rows = row == null ? 0 : 1;
        }
        frame.rowsModified(rows);
        if (rows > 0) {
            savepoints.changed();
        }
        return row == null ? null : assignable(row, dml.into(), frame);
    }

    /**
     * {@code EXECUTE STATEMENT} local. Não muda o {@code ROW_COUNT}, como no motor.
     *
     * @return como em {@link #execute(DmlStatement, Frame)}
     */
    List<Object> execute(ExecuteStatement execute, Frame frame) throws SQLException {
        Dynamic dynamic = dynamic(execute, frame);
        boolean query = DynamicSql.isQuery(dynamic.sql());
        if (!query) {
            frame.savepoints().beforeChange();
        }
        boolean changed;
        List<Object> row = null;
        if (execute.into().isEmpty()) {
            try (PreparedStatement statement = Statements.prepare(connection, dynamic.sql(), dynamic.parameters())) {
                boolean results = statement.execute();
                // Sem contagem de linhas (-1) ou com resultado não há como saber se alterou: conta como alteração.
                changed = !query && (results || statement.getUpdateCount() != 0);
            }
        } else {
            row = Savepoints.undoneOnError(connection,
                    () -> singleRow(dynamic.sql(), dynamic.parameters(), execute.into().size()));
            changed = !query;
        }
        if (changed) {
            frame.savepoints().changed();
        }
        return row == null ? null : assignable(row, execute.into(), frame);
    }

    /**
     * {@code EXECUTE PROCEDURE} no servidor, com os argumentos avaliados lá. Não muda o {@code ROW_COUNT}.
     *
     * @param name nome da procedure para o servidor (ver {@link Frame#serverName})
     * @return as saídas, na ordem do {@code RETURNS}, como o servidor as devolve; vazio se não houver
     */
    List<Object> executeProcedure(String name, List<Expression> arguments, Frame frame) throws SQLException {
        StringBuilder sql = new StringBuilder("EXECUTE PROCEDURE ").append(name);
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            BoundSql bound = BoundSql.bind(arguments.get(i), frame);
            sql.append(i == 0 ? "(" : ", ").append(bound.sql());
            parameters.addAll(bound.parameters());
        }
        if (!arguments.isEmpty()) {
            sql.append(')');
        }
        try (PreparedStatement statement = Statements.prepare(connection, sql.toString(), parameters)) {
            if (!statement.execute()) {
                return List.of();
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                if (!resultSet.next()) {
                    return List.of();
                }
                int columns = resultSet.getMetaData().getColumnCount();
                List<Object> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    row.add(resultSet.getObject(i));
                }
                return row;
            }
        }
    }

    /** Abre o cursor de um {@code FOR SELECT} ou {@code OPEN}, com os {@code :var} valendo o que valem agora. */
    void open(Cursor cursor, DmlStatement select, Frame frame) throws SQLException {
        BoundSql bound = BoundSql.bind(select.sql(), frame);
        Set<String> variables = new HashSet<>();
        for (Expression.Part part : select.sql().parts()) {
            if (part instanceof Expression.Name name && frame.find(name.name()).isPresent()) {
                variables.add(name.name());
            } else if (part instanceof Expression.Field field
                    && frame.find(field.qualifier() + "." + field.name()).isPresent()) {
                variables.add(field.qualifier() + "." + field.name()); // NEW.campo numa trigger
            }
        }
        cursor.open(connection, bound.sql(), bound.parameters(), variables);
    }

    /**
     * Abre o cursor de um {@code FOR EXECUTE STATEMENT}. Os parâmetros são de outro statement e o motor os avalia
     * uma vez só, como aqui.
     */
    void open(Cursor cursor, ExecuteStatement execute, Frame frame) throws SQLException {
        Dynamic dynamic = dynamic(execute, frame);
        cursor.open(connection, dynamic.sql(), dynamic.parameters(), Set.of());
    }

    /** Linha lida do servidor convertida para os tipos dos alvos do {@code INTO}, como numa atribuição. */
    List<Object> assignable(List<Object> row, List<String> targets, Frame frame) throws SQLException {
        return evaluator.assignable(row, targets.stream().map(target -> frame.get(target).type()).toList());
    }

    /** Texto e parâmetros de um statement dinâmico. */
    private record Dynamic(String sql, List<Object> parameters) {
    }

    private Dynamic dynamic(ExecuteStatement execute, Frame frame) throws SQLException {
        Object text = evaluator.value(execute.sql(), frame);
        if (text == null) {
            throw new PsqlExecutionException("EXECUTE STATEMENT com texto NULL");
        }
        String sql = text.toString();
        List<Object> parameters = new ArrayList<>();
        if (execute.parameters().stream().anyMatch(parameter -> parameter.name() != null)) {
            DynamicSql.Positional positional = DynamicSql.withPositionalParameters(sql);
            sql = positional.sql();
            for (String name : positional.names()) {
                ExecuteStatement.Parameter parameter = execute.parameters().stream()
                        .filter(candidate -> Objects.equals(candidate.name(), name))
                        .findFirst()
                        .orElseThrow(() -> new PsqlExecutionException(
                                "Parâmetro do EXECUTE STATEMENT não informado: " + name));
                parameters.add(evaluator.value(parameter.value(), frame));
            }
        } else {
            for (ExecuteStatement.Parameter parameter : execute.parameters()) {
                parameters.add(evaluator.value(parameter.value(), frame));
            }
        }
        return new Dynamic(sql, parameters);
    }

    /**
     * Linhas afetadas por um {@code MERGE}. O update count do Jaybird não soma as linhas inseridas às atualizadas,
     * mas o {@code RETURNING} (multilinha no Firebird 5) devolve uma linha por linha afetada. Em PSQL não existe
     * {@code RETURNING} sem {@code INTO}, então o texto ainda não tem um.
     */
    private long mergedRows(BoundSql bound) throws SQLException {
        try (PreparedStatement statement =
                Statements.prepare(connection, bound.sql() + "\nRETURNING 1", bound.parameters());
                ResultSet resultSet = statement.executeQuery()) {
            long rows = 0;
            while (resultSet.next()) {
                rows++;
            }
            return rows;
        }
    }

    /** Primeira linha, ou {@code null} se não houver; uma segunda linha é o erro de singleton do motor. */
    private List<Object> singleRow(String sql, List<Object> parameters, int columns) throws SQLException {
        try (PreparedStatement statement = Statements.prepare(connection, sql, parameters)) {
            if (!statement.execute()) {
                return null;
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                if (!resultSet.next()) {
                    return null;
                }
                List<Object> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    row.add(resultSet.getObject(i));
                }
                if (resultSet.next()) {
                    throw ServerErrors.multipleRowsInSingletonSelect();
                }
                return row;
            }
        }
    }
}
