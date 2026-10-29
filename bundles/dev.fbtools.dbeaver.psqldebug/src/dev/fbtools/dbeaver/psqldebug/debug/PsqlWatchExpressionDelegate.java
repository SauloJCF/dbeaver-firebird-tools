package dev.fbtools.dbeaver.psqldebug.debug;

import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IWatchExpressionDelegate;
import org.eclipse.debug.core.model.IWatchExpressionListener;
import org.eclipse.debug.core.model.IWatchExpressionResult;

import dev.fbtools.dbeaver.psqldebug.interp.PsqlExecutionException;
import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;

/**
 * Watches da view Expressions: cada expressão é avaliada no servidor (Modo Careful) com as variáveis do frame
 * selecionado, na thread da execução pausada ({@link PsqlDebugTarget#watch}). O debug.ui pede a avaliação a cada
 * mudança do contexto de debug: a cada pausa, que traz frames novos, e a cada frame selecionado na view Debug. Um erro
 * do servidor ou do parser fica no lugar do valor, e a sessão segue pausada.
 */
public final class PsqlWatchExpressionDelegate implements IWatchExpressionDelegate {

    private static final ILog LOG = ILog.of(PsqlWatchExpressionDelegate.class);

    /** Não espera: o debug.ui chama na thread da UI, e o resultado chega depois ao {@code listener}. */
    @Override
    public void evaluateExpression(String expression, IDebugElement context, IWatchExpressionListener listener) {
        PsqlStackFrame frame = frameOf(context);
        if (frame == null) {
            listener.watchEvaluationFinished(null); // sem pausa, o watch fica sem valor
            return;
        }
        frame.watch(expression).whenComplete((value, error) -> listener.watchEvaluationFinished(error == null
                ? new Result(expression, new PsqlValue(frame.getDebugTarget(), value.type(), value.value()), null)
                : failure(expression, error)));
    }

    /**
     * Frame da pausa atual em que o watch é avaliado: o selecionado ou, com a thread ou a sessão selecionada, o de
     * cima. {@code null} sem a execução pausada, ou com um frame de uma pausa anterior.
     */
    private static PsqlStackFrame frameOf(IDebugElement context) {
        IStackFrame frame = switch (context) {
            case PsqlStackFrame selected -> selected;
            case PsqlThread thread -> thread.getTopStackFrame();
            case PsqlDebugTarget target -> {
                IThread[] threads = target.getThreads();
                yield threads.length == 0 ? null : ((PsqlThread) threads[0]).getTopStackFrame();
            }
            case null, default -> null;
        };
        return frame instanceof PsqlStackFrame psql && psql.isCurrent() ? psql : null;
    }

    private static Result failure(String expression, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String message = switch (cause) {
            case TimeoutException e -> "O servidor não respondeu a tempo.";
            case SQLException e -> e.getMessage();
            case PsqlSyntaxException e -> e.getMessage();
            case PsqlExecutionException e -> e.getMessage();
            case IllegalStateException e -> e.getMessage();
            default -> {
                LOG.error("Falha ao avaliar o watch " + expression, cause);
                yield cause.toString();
            }
        };
        return new Result(expression, null, new DebugException(Status.error(message, cause)));
    }

    /** O valor do watch, ou o erro no lugar dele. */
    private record Result(String expression, IValue value, DebugException error) implements IWatchExpressionResult {

        @Override
        public IValue getValue() {
            return value;
        }

        @Override
        public boolean hasErrors() {
            return error != null;
        }

        @Override
        public String[] getErrorMessages() {
            return error == null ? new String[0] : new String[] {error.getMessage()};
        }

        @Override
        public String getExpressionText() {
            return expression;
        }

        @Override
        public DebugException getException() {
            return error;
        }
    }
}
