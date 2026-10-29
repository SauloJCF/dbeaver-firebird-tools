package dev.fbtools.dbeaver.psqldebug.debug;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import dev.fbtools.dbeaver.psqldebug.interp.ExecutionControl;
import dev.fbtools.dbeaver.psqldebug.interp.Frame;
import dev.fbtools.dbeaver.psqldebug.interp.Variable;

/**
 * A execução da Rotina. Pausa, resume, steps e suspend vão para o {@link ExecutionControl} da sessão; os stack
 * frames são os frames do Interpretador, do de cima até a Rotina raiz, e só existem com a execução pausada. Os steps
 * pedidos por um stack frame valem para ele, como no Eclipse ({@link PsqlStackFrame}); pedidos pela thread, para o de
 * cima.
 */
final class PsqlThread extends PsqlDebugElement implements IThread {

    private static final IStackFrame[] NO_FRAMES = new IStackFrame[0];
    private static final IBreakpoint[] NO_BREAKPOINTS = new IBreakpoint[0];

    private final PsqlDebugTarget target;
    private volatile boolean suspended;
    private volatile boolean stepping;
    private volatile IStackFrame[] stackFrames = NO_FRAMES;
    private volatile IBreakpoint[] breakpoints = NO_BREAKPOINTS;
    /** Só a thread da execução: o valor de cada variável na pausa anterior. */
    private Map<Variable, Object> previousValues = Map.of();

    PsqlThread(PsqlDebugTarget target) {
        super(target);
        this.target = target;
    }

    void started() {
        fireCreationEvent();
    }

    void terminated() {
        suspended = false;
        stepping = false;
        stackFrames = NO_FRAMES;
        breakpoints = NO_BREAKPOINTS;
        fireTerminateEvent();
    }

    /**
     * Execução pausada: chamado na thread dela, que fica parada até o próximo comando. Os valores das variáveis ficam
     * guardados para a próxima pausa destacar os que mudaram. Cada frame leva as chamadas sem step into do texto dele,
     * para o aviso no editor.
     */
    void paused(ExecutionControl.Pause pause) {
        List<Frame> frames = pause.frames();
        IStackFrame[] built = new IStackFrame[frames.size()];
        Map<Variable, Object> values = new IdentityHashMap<>();
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            built[i] = new PsqlStackFrame(this, target, frame, target.sourceOf(frame), previousValues,
                    target.serverCalls(frame));
            frame.variables().forEach(variable -> values.put(variable, variable.value()));
        }
        previousValues = values;
        stackFrames = built;
        breakpoints = pause.breakpoint() instanceof IBreakpoint breakpoint ? new IBreakpoint[] {breakpoint}
                : NO_BREAKPOINTS;
        stepping = false;
        suspended = true;
        fireSuspendEvent(switch (pause.reason()) {
            case BREAKPOINT -> DebugEvent.BREAKPOINT;
            case STEP -> DebugEvent.STEP_END;
            case REQUEST -> DebugEvent.CLIENT_REQUEST;
        });
    }

    /** Antes de soltar a execução parada: o debug.ui tira a linha destacada. */
    private void resumed(int detail) {
        suspended = false;
        stackFrames = NO_FRAMES;
        breakpoints = NO_BREAKPOINTS;
        fireResumeEvent(detail);
    }

    @Override
    public IStackFrame[] getStackFrames() {
        return isSuspended() ? stackFrames : NO_FRAMES;
    }

    @Override
    public boolean hasStackFrames() {
        return isSuspended() && stackFrames.length > 0;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public IStackFrame getTopStackFrame() {
        IStackFrame[] frames = getStackFrames();
        return frames.length == 0 ? null : frames[0];
    }

    @Override
    public String getName() {
        return target.getName();
    }

    @Override
    public IBreakpoint[] getBreakpoints() {
        return isSuspended() ? breakpoints : NO_BREAKPOINTS;
    }

    @Override
    public boolean canResume() {
        return isSuspended();
    }

    @Override
    public boolean canSuspend() {
        return !suspended && !target.isTerminated();
    }

    @Override
    public boolean isSuspended() {
        return suspended && !target.isTerminated();
    }

    @Override
    public synchronized void resume() {
        if (isSuspended()) {
            resumed(DebugEvent.CLIENT_REQUEST);
            target.control().resume();
        }
    }

    /** Pede pausa no próximo statement; o evento de suspensão sai quando a execução parar. */
    @Override
    public void suspend() {
        target.control().suspend();
    }

    @Override
    public boolean canStepInto() {
        return isSuspended();
    }

    @Override
    public boolean canStepOver() {
        return isSuspended();
    }

    /** Na Rotina raiz, não há para onde voltar. */
    @Override
    public boolean canStepReturn() {
        return isSuspended() && stackFrames.length > 1;
    }

    @Override
    public boolean isStepping() {
        return stepping;
    }

    @Override
    public synchronized void stepInto() {
        if (isSuspended()) {
            stepping = true;
            resumed(DebugEvent.STEP_INTO);
            target.control().stepInto();
        }
    }

    @Override
    public void stepOver() {
        stepOver(null);
    }

    @Override
    public void stepReturn() {
        stepReturn(null);
    }

    /**
     * Step over no frame: para no próximo statement dele, passando pelos frames de cima.
     *
     * @param frame frame da pausa atual; {@code null} para o de cima
     */
    synchronized void stepOver(PsqlStackFrame frame) {
        if (isSuspended() && isCurrent(frame)) {
            stepping = true;
            resumed(DebugEvent.STEP_OVER);
            if (frame == null) {
                target.control().stepOver();
            } else {
                target.control().stepOver(frame.frame());
            }
        }
    }

    /**
     * Step return do frame: para no próximo statement de quem o chamou.
     *
     * @param frame frame da pausa atual; {@code null} para o de cima
     */
    synchronized void stepReturn(PsqlStackFrame frame) {
        if (isSuspended() && isCurrent(frame)) {
            stepping = true;
            resumed(DebugEvent.STEP_RETURN);
            if (frame == null) {
                target.control().stepReturn();
            } else {
                target.control().stepReturn(frame.frame());
            }
        }
    }

    /** O frame é da pausa atual ({@code null} vale como o de cima). */
    private boolean isCurrent(PsqlStackFrame frame) {
        return frame == null || Arrays.asList(stackFrames).contains(frame);
    }

    @Override
    public boolean canTerminate() {
        return target.canTerminate();
    }

    @Override
    public boolean isTerminated() {
        return target.isTerminated();
    }

    @Override
    public void terminate() {
        target.terminate();
    }
}
