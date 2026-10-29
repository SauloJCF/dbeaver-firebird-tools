package dev.fbtools.dbeaver.psqldebug.launch;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * Comando "Depurar Rotina", no menu de contexto do texto do editor de objeto e do editor SQL e no menu "PSQL Debug".
 * Vale sempre o editor ativo, com qualquer parte em foco (o navegador, Breakpoints, Variables, console): fica habilitado
 * só com o editor de uma Rotina Firebird (procedure, function ou trigger de tabela) ou um editor SQL de conexão Firebird
 * (o {@code EXECUTE BLOCK} selecionado, ou a rotina do console de uma package sob o cursor) ativo.
 */
public final class DebugRoutineHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        Object activeEditor = HandlerUtil.getActiveEditor(event);
        DBSObject routine = DebuggableRoutines.find(activeEditor);
        if (routine != null) {
            RoutineLauncher.launch(HandlerUtil.getActiveShell(event), routine);
            return null;
        }
        SQLEditor editor = DebuggableRoutines.findSqlEditor(activeEditor);
        if (editor != null) {
            RoutineLauncher.launch(HandlerUtil.getActiveShell(event), editor);
        }
        return null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        if (evaluationContext instanceof IEvaluationContext context) {
            Object activeEditor = context.getVariable(ISources.ACTIVE_EDITOR_NAME);
            setBaseEnabled(DebuggableRoutines.find(activeEditor) != null
                    || DebuggableRoutines.findSqlEditor(activeEditor) != null);
        }
    }
}
