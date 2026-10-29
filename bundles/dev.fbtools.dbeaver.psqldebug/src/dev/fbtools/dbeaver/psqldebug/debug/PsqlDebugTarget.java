package dev.fbtools.dbeaver.psqldebug.debug;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IThread;

import dev.fbtools.dbeaver.psqldebug.interp.CallPolicy;
import dev.fbtools.dbeaver.psqldebug.interp.DebugSession;
import dev.fbtools.dbeaver.psqldebug.interp.ExecutionControl;
import dev.fbtools.dbeaver.psqldebug.interp.Frame;
import dev.fbtools.dbeaver.psqldebug.interp.ProcedureResult;
import dev.fbtools.dbeaver.psqldebug.interp.ServerCalls;
import dev.fbtools.dbeaver.psqldebug.interp.SessionAbortedException;
import dev.fbtools.dbeaver.psqldebug.interp.StatementHook;
import dev.fbtools.dbeaver.psqldebug.interp.Variable;
import dev.fbtools.dbeaver.psqldebug.interp.WatchValue;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;

/**
 * Sessão de debug no modelo do Eclipse: um alvo com uma thread, a execução da Rotina, que roda numa thread própria.
 * Ela para nos breakpoints da conexão da sessão, inclusive nos das Rotinas chamadas, e nos steps
 * ({@link ExecutionControl}). Na pausa, a edição de variáveis e os watches rodam na thread da execução. As saídas (ou
 * as linhas do {@code SUSPEND}, à medida que são emitidas), o erro ou a interrupção vão para o {@link SessionConsole}.
 * Terminate pede o fim à sessão ({@link DebugSession#abort()}): ela para antes do próximo statement, mesmo pausada, e
 * faz rollback.
 */
public final class PsqlDebugTarget extends PsqlDebugElement implements IDebugTarget {

    /** Abre a Sessão de debug, na thread da execução: a varredura dos Packages temporários já fala com o banco. */
    @FunctionalInterface
    public interface SessionOpener {

        /**
         * @param control o controle da sessão: o gancho que pausa a execução ({@link StatementHook}) e a política das
         *            chamadas, que escolhe entre step into e step over ({@link CallPolicy})
         */
        DebugSession open(ExecutionControl control) throws SQLException;
    }

    private static final ILog LOG = ILog.of(PsqlDebugTarget.class);
    /** Quanto a UI espera por uma tarefa na pausa (editar uma variável, avaliar um watch). */
    private static final int TASK_TIMEOUT_SECONDS = 30;

    /** Breakpoint ativo numa linha de um texto de Rotina. */
    private record Location(SourceId source, int line) {
    }

    private final ILaunch launch;
    private final String dataSourceId;
    private final SourceId rootSource;
    private final RoutineDefinition routine;
    private final List<Object> inputs;
    private final boolean commitOnFinish;
    private final SessionOpener opener;
    private final AutoCloseable connectionOwner;
    private final SessionConsole console;
    private final PsqlThread thread;
    private final ExecutionControl control;
    private volatile Map<Location, PsqlLineBreakpoint> breakpoints = Map.of();
    private volatile DebugSession session;
    private volatile boolean terminateRequested;
    private volatile boolean terminated;

    /**
     * @param rootSource texto da Rotina raiz, na conexão do DBeaver de cujas Rotinas são os breakpoints da sessão:
     *            num {@code EXECUTE BLOCK}, o do editor SQL de onde ele saiu
     * @param routine a Rotina raiz; com {@code SUSPEND}, e sempre num {@code EXECUTE BLOCK}, roda como selecionável,
     *            e cada linha emitida vai para o console na hora
     * @param inputs valores das entradas, na ordem; os que faltarem no fim usam o valor padrão
     * @param connectionOwner dono da conexão da sessão (o contexto isolado do DBeaver), fechado ao fim
     */
    public PsqlDebugTarget(ILaunch launch, SourceId rootSource, RoutineDefinition routine, List<Object> inputs,
            boolean commitOnFinish, SessionOpener opener, AutoCloseable connectionOwner, SessionConsole console) {
        super(null);
        this.launch = launch;
        this.dataSourceId = rootSource.dataSourceId();
        this.rootSource = rootSource;
        this.routine = routine;
        this.inputs = inputs;
        this.commitOnFinish = commitOnFinish;
        this.opener = opener;
        this.connectionOwner = connectionOwner;
        this.console = console;
        this.thread = new PsqlThread(this);
        this.control = new ExecutionControl(new ExecutionControl.Breakpoints() {
            @Override
            public Object at(RoutineDefinition source, int line) {
                return breakpointAt(source, line);
            }

            @Override
            public boolean none() {
                return breakpoints.isEmpty() || !breakpointManager().isEnabled();
            }
        }, thread::paused, this::frames);
    }

    /** Anuncia o alvo e a thread, passa a acompanhar os breakpoints e começa a execução. */
    public void start() {
        fireCreationEvent();
        thread.started();
        breakpointManager().addBreakpointListener(this);
        indexBreakpoints();
        Thread execution = new Thread(this::execute, "Firebird PSQL debug: " + routine.displayName());
        execution.setDaemon(true);
        execution.start();
    }

    private void execute() {
        console.started(routine, inputs);
        try (DebugSession opened = opener.open(control)) {
            session = opened;
            if (terminateRequested) {
                opened.abort();
            }
            if (routine.kind() == RoutineDefinition.Kind.EXECUTE_BLOCK || routine.selectable()) {
                List<List<Object>> rows = new ArrayList<>();
                opened.select(routine, inputs, row -> {
                    rows.add(row);
                    console.row(routine, rows.size(), row);
                });
                console.finished(routine, rows, commitOnFinish);
            } else {
                ProcedureResult result = opened.run(routine, inputs);
                console.finished(routine, result, commitOnFinish);
            }
        } catch (SessionAbortedException e) {
            console.aborted();
        } catch (SQLException e) {
            console.failed(e);
        } catch (RuntimeException e) {
            console.failed(e);
            LOG.error("Falha na Sessão de debug de " + routine.displayName(), e);
        } finally {
            breakpointManager().removeBreakpointListener(this);
            closeConnectionOwner();
            terminated = true;
            thread.terminated();
            fireTerminateEvent();
        }
    }

    private void closeConnectionOwner() {
        try {
            connectionOwner.close();
        } catch (Exception e) {
            LOG.warn("Falha ao fechar a conexão da Sessão de debug", e);
        }
    }

    ExecutionControl control() {
        return control;
    }

    /** Pilha de frames da execução; só é consistente na thread dela. */
    private List<Frame> frames() {
        DebugSession current = session;
        return current == null ? List.of() : current.frames();
    }

    /**
     * Valor novo para uma variável, digitado com a execução pausada ({@link DebugSession#assign}). Roda na thread da
     * execução, que é a dona da conexão, e espera o resultado.
     *
     * @throws DebugException com a mensagem do servidor, se o texto não serve para o tipo; a variável fica como estava
     */
    void assign(Frame frame, Variable variable, String text) throws DebugException {
        DebugSession current = session;
        if (current == null || !thread.isSuspended()) {
            throw new DebugException(Status.error("A execução não está pausada."));
        }
        try {
            control.whilePaused(() -> {
                current.assign(frame, variable, text);
                return null;
            }).get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String reason = cause instanceof SQLException ? "o servidor não converteu o valor para "
                    + variable.type().strip() + ": " + cause.getMessage() : cause.getMessage();
            throw new DebugException(Status.error(variable.name() + " não foi alterada: " + reason, cause));
        } catch (TimeoutException e) {
            throw new DebugException(Status.error(variable.name() + ": o servidor não respondeu a tempo.", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DebugException(Status.error(variable.name() + " não foi alterada.", e));
        }
    }

    /**
     * Valor de um watch com as variáveis do frame ({@link DebugSession#watch}), avaliado na thread da execução
     * pausada, que é a dona da conexão. Não espera: o valor chega pelo futuro.
     *
     * @return falha com o erro do servidor ou do parser, ou com {@link TimeoutException} se o servidor não responder a
     *         tempo; a sessão segue pausada
     */
    CompletableFuture<WatchValue> watch(Frame frame, String text) {
        DebugSession current = session;
        if (current == null || !thread.isSuspended()) {
            return CompletableFuture.failedFuture(new IllegalStateException("A execução não está pausada."));
        }
        return control.whilePaused(() -> current.watch(frame, text)).orTimeout(TASK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
    }

    /** Texto de que são as posições do frame. */
    SourceId sourceOf(Frame frame) {
        return sourceOf(frame.sourceRoutine());
    }

    /** O texto da Rotina raiz é o do launch; o das outras sai do nome delas. */
    private SourceId sourceOf(RoutineDefinition source) {
        return source == routine ? rootSource : SourceId.of(dataSourceId, source);
    }

    /**
     * Chamadas sem step into do texto do frame, para o aviso no editor ({@link DebugSession#serverCalls}). Na thread da
     * execução, na pausa; sem aviso se o servidor falhar ao ler os metadados.
     */
    List<ServerCalls.Call> serverCalls(Frame frame) {
        DebugSession current = session;
        if (current == null) {
            return List.of();
        }
        try {
            return current.serverCalls(frame.sourceRoutine());
        } catch (SQLException | RuntimeException e) {
            LOG.warn("Falha ao procurar as chamadas executadas no servidor de " + frame.sourceRoutine().displayName(),
                    e);
            return List.of();
        }
    }

    /** Na thread da execução, a cada statement. "Pular todos os breakpoints" da view Breakpoints vale aqui. */
    private Object breakpointAt(RoutineDefinition source, int line) {
        Map<Location, PsqlLineBreakpoint> index = breakpoints;
        if (index.isEmpty() || !breakpointManager().isEnabled()) {
            return null;
        }
        return index.get(new Location(sourceOf(source), line));
    }

    /** Refaz o índice dos breakpoints ativos da conexão, a cada mudança neles. */
    private synchronized void indexBreakpoints() {
        Map<Location, PsqlLineBreakpoint> index = new HashMap<>();
        for (IBreakpoint breakpoint : breakpointManager().getBreakpoints(MODEL_ID)) {
            if (breakpoint instanceof PsqlLineBreakpoint line && supportsBreakpoint(line)) {
                try {
                    if (line.isEnabled()) {
                        index.putIfAbsent(new Location(line.source(), line.getLineNumber()), line);
                    }
                } catch (CoreException e) {
                    // o marker foi removido no meio da leitura; o evento de remoção refaz o índice
                }
            }
        }
        breakpoints = Map.copyOf(index);
    }

    private static IBreakpointManager breakpointManager() {
        return DebugPlugin.getDefault().getBreakpointManager();
    }

    @Override
    public IDebugTarget getDebugTarget() {
        return this;
    }

    @Override
    public ILaunch getLaunch() {
        return launch;
    }

    @Override
    public IProcess getProcess() {
        return null;
    }

    @Override
    public IThread[] getThreads() {
        return terminated ? new IThread[0] : new IThread[] {thread};
    }

    @Override
    public boolean hasThreads() {
        return !terminated;
    }

    @Override
    public String getName() {
        return routine.displayName();
    }

    @Override
    public boolean supportsBreakpoint(IBreakpoint breakpoint) {
        if (!(breakpoint instanceof PsqlLineBreakpoint line)) {
            return false;
        }
        SourceId source = line.source();
        return source != null && dataSourceId.equals(source.dataSourceId());
    }

    @Override
    public boolean canTerminate() {
        return !terminated && !terminateRequested;
    }

    @Override
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public void terminate() {
        terminateRequested = true;
        DebugSession current = session;
        if (current != null) {
            current.abort();
        }
        control.release(); // solta a execução pausada, que então para no aborto
    }

    @Override
    public boolean canResume() {
        return thread.canResume();
    }

    @Override
    public boolean canSuspend() {
        return thread.canSuspend();
    }

    @Override
    public boolean isSuspended() {
        return thread.isSuspended();
    }

    @Override
    public void resume() {
        thread.resume();
    }

    @Override
    public void suspend() {
        thread.suspend();
    }

    @Override
    public void breakpointAdded(IBreakpoint breakpoint) {
        if (breakpoint instanceof PsqlLineBreakpoint) {
            indexBreakpoints();
        }
    }

    @Override
    public void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta delta) {
        if (breakpoint instanceof PsqlLineBreakpoint) {
            indexBreakpoints();
        }
    }

    @Override
    public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta delta) {
        if (breakpoint instanceof PsqlLineBreakpoint) {
            indexBreakpoints();
        }
    }

    @Override
    public boolean canDisconnect() {
        return false;
    }

    @Override
    public void disconnect() {
    }

    @Override
    public boolean isDisconnected() {
        return false;
    }

    @Override
    public boolean supportsStorageRetrieval() {
        return false;
    }

    @Override
    public IMemoryBlock getMemoryBlock(long startAddress, long length) {
        return null;
    }
}
