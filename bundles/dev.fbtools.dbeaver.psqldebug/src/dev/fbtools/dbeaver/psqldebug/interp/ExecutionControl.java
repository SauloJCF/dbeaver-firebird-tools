package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import dev.fbtools.dbeaver.psqldebug.psql.CallSites;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Block;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;

/**
 * Pausa da execução de uma Sessão de debug: breakpoint, step (over, into, return) e pedido de pausa. É o
 * {@link StatementHook} e a {@link CallPolicy} da sessão: roda na thread da execução e, numa pausa, a deixa parada
 * até o próximo comando, que vem de outra thread ({@link #resume()}, {@link #stepOver()}, {@link #stepInto()},
 * {@link #stepReturn()}, {@link #release()}). Parada, ela roda as tarefas de {@link #whilePaused}.
 * <p>
 * Só para num frame que a camada de debug acompanha ({@link Frame#steppedInto()}), e nunca num bloco
 * ({@code BEGIN}). Um breakpoint vale ao entrar na linha: não para de novo num statement que vem depois de outro da
 * mesma linha, no mesmo frame ({@code IF (...) THEN X = 1;}), mas para de novo quando um laço volta à linha.
 * <ul>
 * <li>O step over para no próximo statement do mesmo frame ou de quem o chamou, e não dentro das Rotinas que ele
 * chama, fora de um breakpoint.</li>
 * <li>O step into entra na Rotina da chamada Step into elegível do statement em que a execução parou e para no
 * primeiro statement dela. Sem chamada que dê para interpretar, para no próximo statement, como o step over.</li>
 * <li>O step return para no próximo statement de quem chamou o frame. No frame da Rotina raiz, vai até o fim, como o
 * resume.</li>
 * </ul>
 * O step over e o step return podem ser pedidos para um frame de baixo da pilha da pausa, como no Eclipse: o step over
 * para no próximo statement desse frame (ou de quem o chamou), e o step return, no de quem o chamou.
 * <p>
 * Como política de chamadas: a chamada é interpretada e acompanhada no step into do statement dela, ou quando há um
 * breakpoint numa linha que ela alcança, para que ele valha também no step over e no resume. As outras vão ao
 * servidor (ver {@link CallPolicy}).
 */
public final class ExecutionControl implements StatementHook, CallPolicy {

    /** Breakpoints ativos, consultados na thread da execução a cada statement e a cada chamada. */
    @FunctionalInterface
    public interface Breakpoints {

        /** Nenhum breakpoint. */
        Breakpoints NONE = new Breakpoints() {
            @Override
            public Object at(RoutineDefinition source, int line) {
                return null;
            }

            @Override
            public boolean none() {
                return true;
            }
        };

        /**
         * @param source Rotina de cujo texto é a linha ({@link Frame#sourceRoutine()})
         * @param line linha, a partir de 1
         * @return o breakpoint ativo na linha, ou {@code null}
         */
        Object at(RoutineDefinition source, int line);

        /**
         * Não há breakpoint ativo em nenhum texto: dispensa procurá-los nas Rotinas chamadas, o que carregaria a Fonte
         * do banco delas.
         */
        default boolean none() {
            return false;
        }
    }

    /** Por que a execução parou. */
    public enum Reason {
        BREAKPOINT, STEP, REQUEST
    }

    /**
     * Execução parada.
     *
     * @param breakpoint o breakpoint, quando a razão é {@link Reason#BREAKPOINT}; senão {@code null}
     * @param frames pilha de frames, do de cima (onde a execução parou) até a Rotina raiz
     */
    public record Pause(Reason reason, Object breakpoint, List<Frame> frames) {

        public Pause {
            frames = List.copyOf(frames);
        }
    }

    /** Quem mostra a pausa. */
    @FunctionalInterface
    public interface Listener {

        /** Chamado na thread da execução, que depois fica parada até o próximo comando. */
        void paused(Pause pause);
    }

    private enum Command {
        RESUME, STEP_OVER, STEP_INTO, STEP_RETURN
    }

    private final Breakpoints breakpoints;
    private final Listener listener;
    private final Supplier<List<Frame>> stack;

    // Só a thread da execução: o último statement visto, para o breakpoint valer ao entrar na linha.
    private Frame lastFrame;
    private SourcePosition lastPosition;

    // Protegidos por this.
    private boolean paused;
    /** Pilha da pausa em andamento, do frame de cima à raiz; vazia fora da pausa. */
    private List<Frame> pausedFrames = List.of();
    private Command command;
    /** Altura, contada da raiz (1), do frame a que o comando se refere. */
    private int commandLevel;
    /** Com um step pendente, a altura máxima da pilha em que ele para; senão -1. */
    private int stepDepth = -1;
    /** Com um step into pendente, o frame em que a execução parou, cujas chamadas são interpretadas. */
    private Frame stepIntoCaller;
    private boolean pauseRequested;
    private boolean released;
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    /** @param stack pilha de frames da execução ({@link DebugSession#frames()}), lida na thread da execução */
    public ExecutionControl(Breakpoints breakpoints, Listener listener, Supplier<List<Frame>> stack) {
        this.breakpoints = breakpoints;
        this.listener = listener;
        this.stack = stack;
    }

    @Override
    public void beforeStatement(Frame frame, Statement statement) {
        if (statement instanceof Block) {
            return;
        }
        SourcePosition position = statement.position();
        boolean enteringLine = frame != lastFrame || position.line() != lastPosition.line()
                || position.column() <= lastPosition.column();
        lastFrame = frame;
        lastPosition = position;
        if (!frame.steppedInto()) {
            return;
        }
        Object breakpoint = enteringLine ? breakpoints.at(frame.sourceRoutine(), position.line()) : null;
        List<Frame> frames;
        Reason reason;
        synchronized (this) {
            if (released) {
                return;
            }
            frames = breakpoint != null || stepDepth >= 0 || pauseRequested ? stack.get() : null;
            if (breakpoint != null) {
                reason = Reason.BREAKPOINT;
            } else if (stepDepth >= 0 && frames.size() <= stepDepth) {
                reason = Reason.STEP;
            } else if (pauseRequested) {
                reason = Reason.REQUEST;
            } else {
                return;
            }
            stepDepth = -1;
            stepIntoCaller = null;
            pauseRequested = false;
            command = null;
            pausedFrames = List.copyOf(frames);
            paused = true;
        }
        listener.paused(new Pause(reason, breakpoint, frames));
        await();
    }

    /**
     * Fica parado até um comando, rodando as tarefas pedidas por {@link #whilePaused} antes dele; com um step, guarda
     * onde ele para.
     */
    private void await() {
        boolean interrupted = false;
        while (true) {
            Runnable task;
            synchronized (this) {
                while (command == null && tasks.isEmpty() && !released) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                        released = true;
                    }
                }
                task = tasks.poll();
                if (task == null) {
                    if (command != null && !released) {
                        startStep();
                    }
                    command = null;
                    paused = false;
                    pausedFrames = List.of();
                    break;
                }
            }
            task.run();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Onde o comando que soltou a execução a faz parar de novo. Com o lock. */
    private void startStep() {
        switch (command) {
            case STEP_OVER -> stepDepth = commandLevel;
            case STEP_INTO -> {
                stepDepth = Integer.MAX_VALUE;
                stepIntoCaller = pausedFrames.get(0);
            }
            case STEP_RETURN -> stepDepth = commandLevel > 1 ? commandLevel - 1 : -1;
            case RESUME -> {
            }
        }
    }

    /**
     * Roda uma tarefa na thread da execução parada, que é a única a usar a conexão e o estado do Interpretador (editar
     * uma variável, avaliar um watch). As tarefas rodam na ordem pedida, antes do comando que solta a execução.
     *
     * @return o resultado; falha com {@link IllegalStateException} se a execução não está parada
     */
    public synchronized <T> CompletableFuture<T> whilePaused(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!paused || released) {
            result.completeExceptionally(new IllegalStateException("A execução não está pausada"));
            return result;
        }
        tasks.add(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        notifyAll();
        return result;
    }

    /** A execução está parada. */
    public synchronized boolean isPaused() {
        return paused;
    }

    /** Continua a execução parada até o próximo breakpoint. Sem efeito se ela não está parada. */
    public synchronized void resume() {
        command(Command.RESUME, null);
    }

    /**
     * Executa o statement em que a execução parou e para no próximo (ver a classe). Sem efeito se ela não está
     * parada.
     */
    public synchronized void stepOver() {
        command(Command.STEP_OVER, null);
    }

    /**
     * Continua até o próximo statement do frame (ou de quem o chamou), passando pelos frames de cima dele. Sem efeito
     * se a execução não está parada ou se o frame não é da pilha da pausa.
     */
    public synchronized void stepOver(Frame frame) {
        command(Command.STEP_OVER, frame);
    }

    /**
     * Executa o statement em que a execução parou, entrando na Rotina da chamada Step into elegível dele, e para no
     * próximo statement, que é o primeiro dela (ver a classe). Sem efeito se a execução não está parada.
     */
    public synchronized void stepInto() {
        command(Command.STEP_INTO, null);
    }

    /**
     * Continua até sair do frame de cima e para no próximo statement de quem o chamou; na Rotina raiz, até o fim. Sem
     * efeito se a execução não está parada.
     */
    public synchronized void stepReturn() {
        command(Command.STEP_RETURN, null);
    }

    /**
     * Continua até sair do frame e para no próximo statement de quem o chamou. Sem efeito se a execução não está
     * parada ou se o frame não é da pilha da pausa.
     */
    public synchronized void stepReturn(Frame frame) {
        command(Command.STEP_RETURN, frame);
    }

    /** @param frame frame da pilha da pausa a que o comando se refere; {@code null} para o de cima */
    private void command(Command next, Frame frame) {
        int index = frame == null ? 0 : pausedFrames.indexOf(frame);
        if (!paused || index < 0) {
            return;
        }
        command = next;
        commandLevel = pausedFrames.size() - index;
        notifyAll();
    }

    /** Pede pausa no próximo statement em que a execução pode parar. Sem efeito com ela já parada. */
    public synchronized void suspend() {
        if (!paused) {
            pauseRequested = true;
        }
    }

    /**
     * Solta a execução parada e não para mais, para a sessão terminar: com {@link DebugSession#abort()}, ela é
     * interrompida logo depois.
     */
    public synchronized void release() {
        released = true;
        notifyAll();
    }

    /** No step into pedido na pausa em que o frame de quem chama estava em cima, a chamada é interpretada. */
    @Override
    public synchronized boolean stepInto(CallSites.CallSite call, Frame caller) {
        return !released && caller == stepIntoCaller;
    }

    /** Há um breakpoint ativo numa linha que a chamada alcança. */
    @Override
    public boolean stopsInside(Reach reach) throws SQLException {
        synchronized (this) {
            if (released) {
                return false;
            }
        }
        if (breakpoints.none()) {
            return false;
        }
        for (Map.Entry<RoutineDefinition, NavigableSet<Integer>> text : reach.lines().entrySet()) {
            for (int line : text.getValue()) {
                if (breakpoints.at(text.getKey(), line) != null) {
                    return true;
                }
            }
        }
        return false;
    }
}
