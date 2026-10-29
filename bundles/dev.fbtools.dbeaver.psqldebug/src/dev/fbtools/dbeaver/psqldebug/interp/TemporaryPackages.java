package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.fbtools.dbeaver.psqldebug.psql.Identifiers;
import dev.fbtools.dbeaver.psqldebug.psql.SubroutineScopes;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;

/**
 * Packages temporários de debug de uma Sessão de debug (ADR 0003): cada Rotina com sub-rotinas ganha um package
 * {@code DBG$<conexão>_<n>} com cópias delas, para que uma expressão ou Fatia DML que as chama rode no servidor. O
 * package é criado na primeira vez que uma chamada no servidor precisa dele.
 * <p>
 * O DDL roda num {@code EXECUTE STATEMENT ... WITH AUTONOMOUS TRANSACTION}, na própria conexão da sessão: o package
 * fica visível para a transação da sessão (também em {@code SNAPSHOT}) sem que nada dela seja commitado. O número da
 * conexão ({@code CURRENT_CONNECTION}) no nome deixa a varredura ({@link #sweep}) distinguir os restos de sessões que
 * já não existem dos packages de outra sessão em andamento.
 */
public final class TemporaryPackages {

    private static final String PREFIX = "DBG$";

    private static final String DDL_SQL = """
            EXECUTE BLOCK (DDL BLOB SUB_TYPE TEXT = ?)
            AS
            BEGIN
              EXECUTE STATEMENT :DDL WITH AUTONOMOUS TRANSACTION;
            END""";

    private final Connection connection;
    /** Package pronto de cada Rotina, por identidade. */
    private final Map<RoutineDefinition, String> packages = new IdentityHashMap<>();
    /** Packages criados, inclusive o cabeçalho de um cujo corpo o servidor recusou. */
    private final List<String> created = new ArrayList<>();
    private long attachment = -1;

    /** @param connection conexão da Sessão de debug */
    public TemporaryPackages(Connection connection) {
        this.connection = connection;
    }

    /**
     * Package com as cópias das sub-rotinas da Rotina, criado se ainda não existe. As sub-rotinas que usam variáveis
     * da Rotina ficam de fora: a cópia delas não compilaria, e o Pré-check garante que nenhuma chamada no servidor
     * precisa delas ({@link SubroutineScopes}).
     *
     * @return nome do package, como está nos metadados
     * @throws PsqlExecutionException se o servidor recusar o package
     */
    String packageFor(RoutineDefinition routine) throws SQLException {
        String name = packages.get(routine);
        if (name != null) {
            return name;
        }
        if (attachment < 0) {
            attachment = currentConnection();
        }
        name = PREFIX + attachment + "_" + (created.size() + 1);
        Set<Subroutine> excluded = SubroutineScopes.usingParentVariables(routine);
        StringBuilder header = new StringBuilder("CREATE PACKAGE " + Identifiers.quote(name) + "\nAS\nBEGIN\n");
        StringBuilder body = new StringBuilder("CREATE PACKAGE BODY " + Identifiers.quote(name) + "\nAS\nBEGIN\n");
        for (Subroutine subroutine : routine.subroutines()) {
            if (!excluded.contains(subroutine)) {
                header.append(subroutine.header()).append(";\n");
                body.append(subroutine.implementation()).append('\n');
            }
        }
        header.append("END");
        body.append("END");
        try {
            ddl(connection, header.toString());
            created.add(name);
            ddl(connection, body.toString());
        } catch (SQLException e) {
            throw new PsqlExecutionException("O servidor recusou o Package temporário de debug " + name
                    + " com as sub-rotinas de " + routine.displayName() + ": " + e.getMessage(), e);
        }
        packages.put(routine, name);
        return name;
    }

    /**
     * Remove os packages criados, mesmo com a transação da sessão em andamento. Tenta todos; se algum não sair, o
     * primeiro erro sobe (com os outros suprimidos) e o package fica para a varredura da próxima sessão.
     */
    public void dropAll() throws SQLException {
        SQLException failure = null;
        for (String name : created) {
            try {
                ddl(connection, "DROP PACKAGE " + Identifiers.quote(name));
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        created.clear();
        packages.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Remove os packages {@code DBG$} deixados por conexões que já não existem: sessão que não chegou ao fim, DBeaver
     * morto. Os de conexões vivas, de outras sessões em andamento, ficam. Um package que não sai (sem privilégio para
     * removê-lo, por exemplo) também fica.
     */
    public static void sweep(Connection connection) throws SQLException {
        List<String> leftovers = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT TRIM(RDB$PACKAGE_NAME) FROM RDB$PACKAGES "
                        + "WHERE RDB$PACKAGE_NAME STARTING WITH '" + PREFIX + "'")) {
            while (resultSet.next()) {
                leftovers.add(resultSet.getString(1));
            }
        }
        for (String name : leftovers) {
            Long owner = attachmentOf(name);
            if (owner != null && isAlive(connection, owner)) {
                continue;
            }
            try {
                ddl(connection, "DROP PACKAGE " + Identifiers.quote(name));
            } catch (SQLException e) {
                // fica para outra varredura
            }
        }
    }

    /** Conexão que criou o package, pelo nome {@code DBG$<conexão>_<n>}; {@code null} se o nome não tem essa forma. */
    private static Long attachmentOf(String name) {
        int separator = name.indexOf('_', PREFIX.length());
        if (separator < 0) {
            return null;
        }
        try {
            return Long.parseLong(name.substring(PREFIX.length(), separator));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isAlive(Connection connection, long attachment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM MON$ATTACHMENTS WHERE MON$ATTACHMENT_ID = ?")) {
            statement.setLong(1, attachment);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private long currentConnection() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT CURRENT_CONNECTION FROM RDB$DATABASE")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static void ddl(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DDL_SQL)) {
            statement.setString(1, sql);
            statement.execute();
        }
    }
}
