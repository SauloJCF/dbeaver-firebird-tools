package dev.fbtools.dbeaver.export.ui;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.ui.UIUtils;

import dev.fbtools.dbeaver.export.extract.MetadataExtractor;

/**
 * Comando "Extract Metadata…": abre o wizard para a conexão Firebird do contexto, a do navegador ou a do editor ativo.
 * A lista de objetos é lida antes, num diálogo de progresso.
 */
public final class ExtractMetadataHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        DBPDataSourceContainer container = FirebirdConnections.find(HandlerUtil.getCurrentSelection(event),
                HandlerUtil.getActivePart(event), HandlerUtil.getActiveEditor(event));
        if (container == null) {
            return null;
        }
        Shell shell = HandlerUtil.getActiveShell(event);
        List<String>[] catalog = loadCatalog(shell, container);
        if (catalog != null) {
            new WizardDialog(shell, new ExtractMetadataWizard(container, catalog[0], catalog[1])).open();
        }
        return null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        if (evaluationContext instanceof IEvaluationContext context) {
            setBaseEnabled(FirebirdConnections.find(context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME),
                    context.getVariable(ISources.ACTIVE_PART_NAME),
                    context.getVariable(ISources.ACTIVE_EDITOR_NAME)) != null);
        }
    }

    /** @return {domínios, generators}, ou {@code null} se falhou (o erro já foi mostrado) */
    @SuppressWarnings("unchecked")
    private static List<String>[] loadCatalog(Shell shell, DBPDataSourceContainer container) {
        List<String>[] catalog = new List[2];
        try {
            UIUtils.runInProgressDialog(monitor -> {
                monitor.beginTask("Lendo os objetos de " + container.getName(), 1);
                DBCExecutionContext context = null;
                try {
                    context = FirebirdConnections.open(monitor, container);
                    MetadataExtractor extractor = new MetadataExtractor(FirebirdConnections.jdbc(monitor, context));
                    catalog[0] = extractor.listDomains();
                    catalog[1] = extractor.listGenerators();
                } catch (DBException | SQLException e) {
                    throw new InvocationTargetException(e);
                } finally {
                    if (context != null) {
                        FirebirdConnections.close(context);
                    }
                    monitor.done();
                }
            });
            return catalog;
        } catch (InvocationTargetException e) {
            UIUtils.showMessageBox(shell, "Extract Metadata", "Não foi possível ler os objetos: "
                    + e.getTargetException().getMessage(), org.eclipse.swt.SWT.ICON_ERROR);
            return null;
        }
    }
}
