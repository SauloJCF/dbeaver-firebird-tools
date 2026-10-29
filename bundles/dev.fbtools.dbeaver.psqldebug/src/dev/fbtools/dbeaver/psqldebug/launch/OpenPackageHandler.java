package dev.fbtools.dbeaver.psqldebug.launch;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

/**
 * Comando "Abrir Package", no menu "PSQL Debug": escolhe uma package da conexão Firebird do contexto (a do navegador ou
 * a do editor ativo) e mostra o corpo dela, que é a Fonte do banco, num console só de leitura. Lá ficam os breakpoints
 * das rotinas da package, e de lá "Depurar Rotina" depura a rotina sob o cursor. O DBeaver não mostra as packages do
 * Firebird.
 */
public final class OpenPackageHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        DBPDataSourceContainer container = DebuggableRoutines.findDataSource(HandlerUtil.getCurrentSelection(event),
                HandlerUtil.getActivePart(event), HandlerUtil.getActiveEditor(event));
        if (container != null) {
            RoutineLauncher.openPackage(HandlerUtil.getActiveShell(event),
                    HandlerUtil.getActiveWorkbenchWindow(event), container);
        }
        return null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        if (evaluationContext instanceof IEvaluationContext context) {
            setBaseEnabled(DebuggableRoutines.findDataSource(
                    context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME),
                    context.getVariable(ISources.ACTIVE_PART_NAME),
                    context.getVariable(ISources.ACTIVE_EDITOR_NAME)) != null);
        }
    }
}
