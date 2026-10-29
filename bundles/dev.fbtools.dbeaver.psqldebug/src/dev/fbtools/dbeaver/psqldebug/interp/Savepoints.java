package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * Savepoint para desfazer uma unidade inteira (statement, bloco com {@code WHEN} ou chamada de Rotina) quando ela
 * falha, ou para desfazer sempre o que a avaliação de um watch alterou.
 */
final class Savepoints {

    private Savepoints() {
    }

    /** Trecho que fala com o servidor. */
    @FunctionalInterface
    interface SqlCall<T> {
        T run() throws SQLException;
    }

    /** Executa sob savepoint: se falhar, desfaz o que o trecho alterou e repassa o erro. */
    static <T> T undoneOnError(Connection connection, SqlCall<T> call) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        T result;
        try {
            result = call.run();
        } catch (SQLException | RuntimeException e) {
            undo(connection, savepoint, e);
            throw e;
        }
        connection.releaseSavepoint(savepoint);
        return result;
    }

    /** Executa sob savepoint e desfaz o que o trecho alterou, com ou sem falha. */
    static <T> T undone(Connection connection, SqlCall<T> call) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        T result;
        try {
            result = call.run();
        } catch (SQLException | RuntimeException e) {
            undo(connection, savepoint, e);
            throw e;
        }
        connection.rollback(savepoint);
        connection.releaseSavepoint(savepoint);
        return result;
    }

    /**
     * {@code ROLLBACK TO SAVEPOINT} e {@code RELEASE SAVEPOINT} depois de uma falha. Se o próprio rollback falhar,
     * o erro dele fica suprimido em {@code cause}, que é o que importa.
     */
    static void undo(Connection connection, Savepoint savepoint, Exception cause) {
        try {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException suppressed) {
            cause.addSuppressed(suppressed);
        }
    }
}
