package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import dev.fbtools.dbeaver.psqldebug.psql.PreCheck;
import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;

/**
 * Sessão de debug: uma execução de Rotina numa conexão dedicada, com uma única transação. Ao terminar ou abortar,
 * faz rollback, a menos que "commit ao terminar" tenha sido pedido. Fecha a conexão no {@link #close()}.
 * <p>
 * Ao abrir, varre os Packages temporários de debug que sobraram de sessões que já não existem; ao terminar ou
 * fechar, remove os que ela criou ({@link TemporaryPackages}).
 */
public final class DebugSession implements AutoCloseable {

    /** Isolamento da transação da sessão. */
    public enum Isolation {
        /** {@code READ COMMITTED}, o padrão. */
        READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
        /** {@code SNAPSHOT}, que o Jaybird chama de {@code REPEATABLE READ}. */
        SNAPSHOT(Connection.TRANSACTION_REPEATABLE_READ);

        private final int level;

        Isolation(int level) {
            this.level = level;
        }
    }

    private final Connection connection;
    private final boolean commitOnFinish;
    private final TemporaryPackages packages;
    private final RoutineCatalog catalog;
    private final Interpreter interpreter;
    /** Só a thread da execução: as chamadas sem step into de cada texto já visto. */
    private final Map<RoutineDefinition, List<ServerCalls.Call>> serverCallsBySource = new IdentityHashMap<>();
    private volatile boolean abortRequested;
    private boolean finished;

    /**
     * Sessão em {@code READ COMMITTED} em que toda chamada de Rotina é executada no servidor.
     *
     * @param connection conexão dedicada à sessão; passa a ser da sessão
     */
    public DebugSession(Connection connection, boolean commitOnFinish) throws SQLException {
        this(connection, commitOnFinish, CallPolicy.SERVER);
    }

    /**
     * Sessão em {@code READ COMMITTED}.
     *
     * @param connection conexão dedicada à sessão; passa a ser da sessão
     * @param policy escolha entre step into e step over a cada chamada elegível
     */
    public DebugSession(Connection connection, boolean commitOnFinish, CallPolicy policy) throws SQLException {
        this(connection, Isolation.READ_COMMITTED, commitOnFinish, policy, StatementHook.NONE);
    }

    /**
     * @param connection conexão dedicada à sessão, ainda sem transação em andamento; passa a ser da sessão
     * @param policy escolha entre step into e step over a cada chamada elegível; com um {@link ExecutionControl}, ele
     *            mesmo, que também é o {@code hook}
     * @param hook chamado antes de cada statement (ver {@link StatementHook}), depois da verificação do
     *            {@link #abort()}
     */
    public DebugSession(Connection connection, Isolation isolation, boolean commitOnFinish, CallPolicy policy,
            StatementHook hook) throws SQLException {
        this.connection = connection;
        this.commitOnFinish = commitOnFinish;
        TemporaryPackages.sweep(connection);
        connection.setTransactionIsolation(isolation.level);
        connection.setAutoCommit(false);
        this.packages = new TemporaryPackages(connection);
        StatementHook abortable = (frame, statement) -> {
            checkAbort();
            hook.beforeStatement(frame, statement);
            checkAbort();
        };
        this.catalog = new DatabaseRoutineCatalog(connection);
        this.interpreter = new Interpreter(connection, catalog, policy, packages, abortable);
    }

    /**
     * Pede o fim da execução, de qualquer thread: ela para antes do próximo statement com
     * {@link SessionAbortedException}, que nenhum {@code WHEN} trata, e a sessão faz rollback (mesmo com "commit ao
     * terminar") e remove os Packages temporários de debug. Um statement que já está no servidor termina antes.
     */
    public void abort() {
        abortRequested = true;
    }

    private void checkAbort() {
        if (abortRequested) {
            throw new SessionAbortedException();
        }
    }

    /** Pilha de frames da execução em andamento (ver {@link Interpreter#frames()}). */
    public List<Frame> frames() {
        return interpreter.frames();
    }

    /**
     * Valor novo para uma variável, digitado com a execução parada (ver {@link Interpreter#assignText}). Na thread da
     * execução: com um {@link ExecutionControl}, por {@link ExecutionControl#whilePaused}.
     */
    public void assign(Frame frame, Variable variable, String text) throws SQLException {
        interpreter.assignText(frame, variable, text);
    }

    /**
     * Valor de um watch no frame, com a execução parada (ver {@link Interpreter#watch}). Na thread da execução: com
     * um {@link ExecutionControl}, por {@link ExecutionControl#whilePaused}.
     *
     * @throws PsqlSyntaxException se o texto não é uma expressão só, ou se altera o estado do banco (ver
     *             {@link RoutineParser#parseWatch})
     * @throws SQLException o erro do servidor ao avaliar a expressão
     */
    public WatchValue watch(Frame frame, String text) throws SQLException {
        return interpreter.watch(frame, RoutineParser.parseWatch(text));
    }

    /**
     * Chamadas sem step into do texto de uma Rotina da pilha, para o aviso no editor (ver {@link ServerCalls}).
     * Calculadas uma vez por texto, na thread da execução: com um {@link ExecutionControl}, numa pausa.
     *
     * @param source Rotina de cujo texto são as posições ({@link Frame#sourceRoutine()})
     */
    public List<ServerCalls.Call> serverCalls(RoutineDefinition source) throws SQLException {
        List<ServerCalls.Call> calls = serverCallsBySource.get(source);
        if (calls == null) {
            calls = List.copyOf(ServerCalls.of(source, catalog));
            serverCallsBySource.put(source, calls);
        }
        return calls;
    }

    /**
     * Executa a Rotina como executável (ver {@link Interpreter#execute}).
     *
     * @throws PsqlExecutionException se o Pré-check recusar a Rotina, antes de executar qualquer coisa
     */
    public ProcedureResult run(RoutineDefinition routine, List<?> inputs) throws SQLException {
        return finish(routine, () -> interpreter.execute(routine, inputs));
    }

    /**
     * Executa a Rotina como selecionável. As linhas do {@code SUSPEND} chegam a {@code rows} à medida que são emitidas,
     * e são o resultado da sessão (ver {@link Interpreter#select}).
     *
     * @throws PsqlExecutionException se o Pré-check recusar a Rotina, antes de executar qualquer coisa
     */
    public void select(RoutineDefinition routine, List<?> inputs, Consumer<List<Object>> rows)
            throws SQLException {
        finish(routine, () -> {
            interpreter.select(routine, inputs, rows);
            return null;
        });
    }

    /** Pré-check, execução e fim da transação. */
    private <T> T finish(RoutineDefinition routine, Execution<T> execution) throws SQLException {
        if (finished) {
            throw new IllegalStateException("A Sessão de debug já terminou");
        }
        List<PreCheck.Finding> findings = PreCheck.scan(routine);
        if (!findings.isEmpty()) {
            throw new PsqlExecutionException("Pré-check recusou " + routine.displayName() + ":\n"
                    + findings.stream().map(PreCheck.Finding::toString).collect(Collectors.joining("\n")));
        }
        finished = true;
        T result;
        try {
            result = execution.run();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(e);
            dropPackagesQuietly(e);
            throw e;
        }
        if (commitOnFinish) {
            connection.commit();
        } else {
            connection.rollback();
        }
        packages.dropAll();
        return result;
    }

    @Override
    public void close() throws SQLException {
        try {
            if (!connection.isClosed()) {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                packages.dropAll();
            }
        } finally {
            connection.close();
        }
    }

    @FunctionalInterface
    private interface Execution<T> {
        T run() throws SQLException;
    }

    private void rollbackQuietly(Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private void dropPackagesQuietly(Exception cause) {
        try {
            packages.dropAll();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }
}
