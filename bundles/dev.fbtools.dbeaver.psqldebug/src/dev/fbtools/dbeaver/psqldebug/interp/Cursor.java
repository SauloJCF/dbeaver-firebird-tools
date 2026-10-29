package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.fbtools.dbeaver.psqldebug.psql.ast.DmlStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Fetch;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;

/**
 * Cursor da Rotina: declarado ({@code DECLARE ... CURSOR}) ou de um {@code FOR SELECT ... AS CURSOR}, aberto no
 * servidor na transação da Sessão de debug; ou, num {@code FOR SELECT} sobre procedure interpretada, sobre as linhas
 * que ela emite ({@link #openRows}).
 *
 * <p>
 * Sem {@code SCROLL}, as linhas são lidas uma a uma do servidor. Com {@code SCROLL}, todas são lidas no
 * {@code OPEN}: desde o Firebird 3 um cursor não vê o que a própria transação altera depois de aberto, então o
 * resultado é o mesmo.
 */
final class Cursor implements AutoCloseable {

    /** Valor de um campo, com o tipo declarado da coluna. */
    record FieldValue(String type, Object value) {
    }

    private final String name;
    private final boolean scroll;
    private final DmlStatement select;
    private final SourcePosition position;

    /** Variáveis que o {@code SELECT} usa, que não podem mudar com o cursor aberto. */
    private Set<String> variables = Set.of();
    private PreparedStatement statement;
    /** Aberto sobre linhas que o Interpretador entrega ({@link #openRows}), sem nada no servidor. */
    private boolean interpreted;
    private ResultSet resultSet;
    private boolean exhausted;
    private List<String> labels;
    private List<String> types;
    /** Com {@code SCROLL}: todas as linhas. */
    private List<List<Object>> rows;
    /** Com {@code SCROLL}: 0 antes da primeira linha, {@code rows.size() + 1} depois da última. */
    private int row;
    /** Linha em que o cursor está, ou {@code null}. */
    private List<Object> current;

    /**
     * @param name nome normalizado, ou {@code null} num {@code FOR} sem cursor nomeado
     * @param select o {@code SELECT} de um cursor declarado, que o {@code OPEN} executa; {@code null} num
     *            {@code FOR}
     * @param position onde o cursor está na Fonte (declaração ou {@code FOR}), para as mensagens
     */
    Cursor(String name, boolean scroll, DmlStatement select, SourcePosition position) {
        this.name = name;
        this.scroll = scroll;
        this.select = select;
        this.position = position;
    }

    String name() {
        return name;
    }

    DmlStatement select() {
        return select;
    }

    boolean isOpen() {
        return statement != null || interpreted;
    }

    /**
     * Cursor de um {@code FOR SELECT} sobre procedure interpretada: cada linha que ela emite chega por
     * {@link #current(List)}.
     *
     * @param labels nomes das colunas, que são os campos do cursor
     * @param types tipos declarados das colunas
     */
    void openRows(List<String> labels, List<String> types) throws SQLException {
        close();
        this.labels = List.copyOf(labels);
        this.types = List.copyOf(types);
        interpreted = true;
    }

    /** Linha atual de um cursor aberto por {@link #openRows}. */
    void current(List<Object> row) {
        current = row;
    }

    /**
     * Recusa ({@link UnsupportedConstruct#CURSOR_VARIABLE_CHANGED}) a alteração de uma variável que o
     * {@code SELECT} do cursor aberto usa: o motor a releria nas próximas linhas.
     */
    void checkUnchanged(Variable variable) {
        if (isOpen() && variables.contains(variable.name())) {
            throw new PsqlExecutionException(position + ": " + UnsupportedConstruct.CURSOR_VARIABLE_CHANGED.syntax()
                    + " (" + variable.name() + ") não é suportada pelo debugger");
        }
    }

    /**
     * {@code OPEN}: um cursor já aberto é fechado e aberto de novo, como no motor.
     *
     * @param variables variáveis que o {@code SELECT} usa
     */
    void open(Connection connection, String sql, List<Object> parameters, Set<String> variables)
            throws SQLException {
        close();
        statement = Statements.prepare(connection, sql, parameters);
        this.variables = Set.copyOf(variables);
        exhausted = false;
        try {
            resultSet = statement.executeQuery();
            ResultSetMetaData metaData = resultSet.getMetaData();
            labels = new ArrayList<>();
            types = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                labels.add(metaData.getColumnLabel(i));
                types.add(ColumnTypes.sqlType(metaData, i));
            }
            if (scroll) {
                rows = new ArrayList<>();
                for (List<Object> next = read(); next != null; next = read()) {
                    rows.add(next);
                }
                row = 0;
            }
        } catch (SQLException | RuntimeException e) {
            try {
                close();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Move o cursor e devolve a linha em que ele parou.
     *
     * @param offset o {@code n} de {@code ABSOLUTE}/{@code RELATIVE}; {@code null} com essas orientações não move
     *            o cursor nem traz linha
     * @return a linha, ou {@code null} se o cursor saiu do resultado
     */
    List<Object> fetch(Fetch.Orientation orientation, Long offset) throws SQLException {
        if (!isOpen()) {
            throw ServerErrors.cursorNotOpen();
        }
        if (!scroll) {
            if (orientation != Fetch.Orientation.NEXT) {
                throw new PsqlExecutionException("FETCH " + orientation + " num cursor sem SCROLL: " + name);
            }
            current = read();
            return current;
        }
        boolean positioned = orientation == Fetch.Orientation.ABSOLUTE || orientation == Fetch.Orientation.RELATIVE;
        if (positioned && offset == null) {
            return null;
        }
        int after = rows.size() + 1;
        long target = switch (orientation) {
            case NEXT -> row + 1L;
            case PRIOR -> row - 1L;
            case FIRST -> 1;
            case LAST -> rows.size();
            case ABSOLUTE -> offset >= 0 ? offset : after + offset;
            case RELATIVE -> row + offset;
        };
        row = (int) Math.max(0, Math.min(after, target));
        current = row >= 1 && row <= rows.size() ? rows.get(row - 1) : null;
        return current;
    }

    /** Campo {@code cursor.nome} da linha atual. */
    FieldValue field(String field) throws SQLException {
        if (!isOpen()) {
            throw ServerErrors.cursorNotOpen();
        }
        if (current == null) {
            throw ServerErrors.cursorNotPositioned(name);
        }
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).equals(field)) {
                return new FieldValue(types.get(i), current.get(i));
            }
        }
        throw new PsqlExecutionException("O cursor " + name + " não tem o campo " + field);
    }

    /** {@code CLOSE}: num cursor já fechado não faz nada, como no motor. */
    @Override
    public void close() throws SQLException {
        PreparedStatement open = statement;
        statement = null;
        interpreted = false;
        resultSet = null;
        rows = null;
        current = null;
        if (open != null) {
            open.close();
        }
    }

    /** Próxima linha do servidor, ou {@code null} no fim. */
    private List<Object> read() throws SQLException {
        if (exhausted || !resultSet.next()) {
            exhausted = true; // o JDBC não garante next() depois do fim
            return null;
        }
        List<Object> row = new ArrayList<>(labels.size());
        for (int i = 1; i <= labels.size(); i++) {
            row.add(resultSet.getObject(i));
        }
        return row;
    }
}
