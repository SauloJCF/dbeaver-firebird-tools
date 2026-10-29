package dev.fbtools.dbeaver.psqldebug.debug;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.debug.core.model.IRegisterGroup;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;

import dev.fbtools.dbeaver.psqldebug.interp.Frame;
import dev.fbtools.dbeaver.psqldebug.interp.ServerCalls;
import dev.fbtools.dbeaver.psqldebug.interp.Variable;
import dev.fbtools.dbeaver.psqldebug.interp.WatchValue;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;

/**
 * Frame do Interpretador numa pausa: a Rotina, a linha do statement em execução e as variáveis dela. A linha é a do
 * texto da Fonte do banco montado pelo {@code RoutineSourceLoader}, que segue o do editor do DBeaver (issue 09).
 * <p>
 * Os steps pedidos por este frame valem para ele, como no Eclipse: o step over para no próximo statement dele, o step
 * return no de quem o chamou. O step into só vale no frame de cima, onde a execução parou.
 */
final class PsqlStackFrame extends PsqlDebugElement implements IStackFrame {

    private final PsqlThread thread;
    private final PsqlDebugTarget target;
    private final Frame frame;
    private final SourceId source;
    private final SourcePosition position;
    private final int line;
    private final IVariable[] variables;
    private final ServerCallWarnings warnings;

    /**
     * @param previous valor de cada variável na pausa anterior, para destacar as que mudaram; sem a variável, ela
     *            não existia
     * @param serverCalls chamadas sem step into do texto do frame, para o aviso no editor
     */
    PsqlStackFrame(PsqlThread thread, PsqlDebugTarget target, Frame frame, SourceId source,
            Map<Variable, Object> previous, List<ServerCalls.Call> serverCalls) {
        super(target);
        this.thread = thread;
        this.target = target;
        this.frame = frame;
        this.source = source;
        this.position = frame.position();
        this.line = position.line();
        this.variables = frame.variables().stream()
                .map(variable -> new PsqlVariable(target, frame, variable, previous.containsKey(variable)
                        && !Objects.deepEquals(previous.get(variable), variable.value())))
                .toArray(IVariable[]::new);
        this.warnings = new ServerCallWarnings(serverCalls);
    }

    /** Frame do Interpretador. */
    Frame frame() {
        return frame;
    }

    /** Texto de que é a linha do frame. */
    SourceId source() {
        return source;
    }

    /** O frame é da pausa em que a execução está parada; o de uma pausa anterior já não vale. */
    boolean isCurrent() {
        return Arrays.asList(thread.getStackFrames()).contains(this);
    }

    /** O frame é o de cima da pausa atual, onde a execução parou. */
    private boolean isTop() {
        return thread.getTopStackFrame() == this;
    }

    /** O frame é o da Rotina raiz da pausa atual. */
    private boolean isBottom() {
        IStackFrame[] frames = thread.getStackFrames();
        return frames.length > 0 && frames[frames.length - 1] == this;
    }

    /** Valor de um watch com as variáveis deste frame (ver {@link PsqlDebugTarget#watch}). */
    CompletableFuture<WatchValue> watch(String text) {
        return target.watch(frame, text);
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == ServerCallWarnings.class) {
            return adapter.cast(warnings);
        }
        return super.getAdapter(adapter);
    }

    @Override
    public IThread getThread() {
        return thread;
    }

    @Override
    public IVariable[] getVariables() {
        return variables;
    }

    @Override
    public boolean hasVariables() {
        return variables.length > 0;
    }

    @Override
    public int getLineNumber() {
        return line;
    }

    @Override
    public int getCharStart() {
        return -1;
    }

    @Override
    public int getCharEnd() {
        return -1;
    }

    /**
     * {@code P linha 12}; numa sub-rotina, {@code SUB (em P) linha 12}. Com chamadas sem step into no statement, o
     * aviso: {@code P linha 12 (executado no servidor: F)}.
     */
    @Override
    public String getName() {
        StringBuilder name = new StringBuilder(frame.routine().displayName());
        if (frame.parent() != null) {
            name.append(" (em ").append(frame.sourceRoutine().displayName()).append(')');
        }
        name.append(" linha ").append(line);
        Set<String> serverCalls = new LinkedHashSet<>();
        for (ServerCalls.Call call : warnings.calls()) {
            if (position.equals(call.statement())) {
                serverCalls.add(call.site().displayName());
            }
        }
        if (!serverCalls.isEmpty()) {
            name.append(" (executado no servidor: ").append(String.join(", ", serverCalls)).append(')');
        }
        return name.toString();
    }

    @Override
    public IRegisterGroup[] getRegisterGroups() {
        return new IRegisterGroup[0];
    }

    @Override
    public boolean hasRegisterGroups() {
        return false;
    }

    @Override
    public boolean canStepInto() {
        return isTop() && thread.canStepInto();
    }

    @Override
    public boolean canStepOver() {
        return isCurrent() && thread.canStepOver();
    }

    /** Na Rotina raiz, não há para onde voltar. */
    @Override
    public boolean canStepReturn() {
        return isCurrent() && !isBottom() && thread.isSuspended();
    }

    @Override
    public boolean isStepping() {
        return thread.isStepping();
    }

    @Override
    public void stepInto() {
        if (isTop()) {
            thread.stepInto();
        }
    }

    @Override
    public void stepOver() {
        thread.stepOver(this);
    }

    @Override
    public void stepReturn() {
        thread.stepReturn(this);
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
    public boolean canTerminate() {
        return thread.canTerminate();
    }

    @Override
    public boolean isTerminated() {
        return thread.isTerminated();
    }

    @Override
    public void terminate() {
        thread.terminate();
    }
}
