package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;

/**
 * Avaliação de expressão no servidor (Modo Careful, ADR 0002): {@code SELECT <expr> FROM RDB$DATABASE}, com
 * cada variável enviada como {@code CAST(? AS <tipo declarado>)}.
 */
final class ExpressionEvaluator {

    private final Connection connection;

    ExpressionEvaluator(Connection connection) {
        this.connection = connection;
    }

    /**
     * Valor da expressão convertido para {@code targetType}, como numa atribuição: o {@code CAST} do servidor
     * arredonda, completa {@code CHAR} e recusa truncamento do mesmo jeito.
     */
    Object evaluate(Expression expression, Frame frame, String targetType) throws SQLException {
        BoundSql bound = BoundSql.bind(expression, frame);
        return selectSingleValue("SELECT CAST((" + bound.sql() + ") AS " + targetType + ") FROM RDB$DATABASE",
                bound.parameters());
    }

    /** Valor da expressão com o tipo que o servidor dá a ela, sem conversão para um alvo. */
    Object value(Expression expression, Frame frame) throws SQLException {
        BoundSql bound = BoundSql.bind(expression, frame);
        return selectSingleValue("SELECT (" + bound.sql() + ") FROM RDB$DATABASE", bound.parameters());
    }

    /** Valor da expressão e o tipo que o servidor dá a ela, como em {@link #value}. */
    WatchValue typedValue(Expression expression, Frame frame) throws SQLException {
        BoundSql bound = BoundSql.bind(expression, frame);
        try (PreparedStatement statement = prepare("SELECT (" + bound.sql() + ") FROM RDB$DATABASE",
                bound.parameters()); ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return new WatchValue(resultSet.getObject(1), ColumnTypes.name(resultSet.getMetaData(), 1));
        }
    }

    /**
     * Condição de {@code IF}/{@code WHILE}. Vai no {@code WHERE}, então só {@code TRUE} devolve linha:
     * {@code FALSE} e desconhecido ({@code NULL}) valem como falso, com a lógica de três valores do servidor.
     */
    boolean isTrue(Expression condition, Frame frame) throws SQLException {
        BoundSql bound = BoundSql.bind(condition, frame);
        try (PreparedStatement statement = prepare("SELECT 1 FROM RDB$DATABASE WHERE (" + bound.sql() + ")",
                bound.parameters()); ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        }
    }

    /** Valor externo (ex.: parâmetro de entrada do launch) convertido para o tipo, como o servidor faria. */
    Object convert(Object value, String targetType) throws SQLException {
        return selectSingleValue("SELECT CAST(? AS " + targetType + ") FROM RDB$DATABASE",
                Collections.singletonList(value));
    }

    /**
     * Valores lidos do servidor (colunas de um {@code SELECT … INTO}, por exemplo) convertidos para os tipos dos
     * alvos como numa atribuição. Cada valor volta ao servidor com um tipo que o representa sem perda
     * ({@link ValueTypes}), e o {@code CAST} para o tipo do alvo é do servidor, não do Jaybird.
     */
    List<Object> assignable(List<Object> values, List<String> targetTypes) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            sql.append(i == 0 ? "" : ", ").append("CAST(")
                    .append(value == null ? "?" : "CAST(? AS " + ValueTypes.sqlType(value) + ")")
                    .append(" AS ").append(targetTypes.get(i)).append(')');
        }
        sql.append(" FROM RDB$DATABASE");
        try (PreparedStatement statement = prepare(sql.toString(), values);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            List<Object> converted = new ArrayList<>(values.size());
            for (int i = 1; i <= values.size(); i++) {
                converted.add(resultSet.getObject(i));
            }
            return converted;
        }
    }

    private Object selectSingleValue(String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = prepare(sql, parameters);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getObject(1);
        }
    }

    private PreparedStatement prepare(String sql, List<Object> parameters) throws SQLException {
        return Statements.prepare(connection, sql, parameters);
    }
}
