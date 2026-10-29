package dev.fbtools.dbeaver.psqldebug.launch;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.navigator.INavigatorModelView;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import dev.fbtools.dbeaver.psqldebug.editor.RoutineEditors;
import dev.fbtools.dbeaver.psqldebug.editor.SqlEditors;

/**
 * Rotina de onde sai um Launch: sempre a do editor ativo, com qualquer parte em foco (o editor, o navegador, uma view do
 * debug ou o console). É a procedure, function ou trigger de tabela do editor de objeto ou, sem ela, o editor SQL de
 * conexão Firebird, de onde sai um {@code EXECUTE BLOCK} ou uma rotina do console de uma package. A seleção do navegador
 * não conta: o launch sai do editor, onde ficam os breakpoints (issue 21).
 */
final class DebuggableRoutines {

    private DebuggableRoutines() {
    }

    /**
     * @param activeEditor editor ativo, que continua definido com uma view em foco. Vale a entrada atual dele, e não a
     *            variável {@code activeEditorInput}, que fica com a entrada provisória de um editor restaurado
     * @return a procedure, a function ou a trigger de tabela Firebird, ou {@code null} se não há
     */
    static DBSObject find(Object activeEditor) {
        if (!(activeEditor instanceof IEditorPart editor
                && editor.getEditorInput() instanceof IDatabaseEditorInput input)) {
            return null;
        }
        DBSObject object = input.getDatabaseObject();
        boolean routine = object instanceof GenericProcedure procedure && RoutineEditors.isFirebird(procedure)
                || RoutineEditors.isTableTrigger(object);
        return routine ? object : null;
    }

    /**
     * Editor SQL de conexão Firebird de onde sai o Launch. Vale só quando {@link #find} não acha Rotina.
     *
     * @param activeEditor editor ativo, que continua definido com uma view em foco
     * @return {@code null} se não há
     */
    static SQLEditor findSqlEditor(Object activeEditor) {
        return SqlEditors.isDebuggable(activeEditor) ? (SQLEditor) activeEditor : null;
    }

    /**
     * Conexão Firebird de onde abrir o corpo de uma package: a do objeto (ou da conexão) selecionado no navegador, ou a
     * do editor ativo (de objeto ou SQL).
     *
     * @return {@code null} se não há conexão Firebird no contexto
     */
    static DBPDataSourceContainer findDataSource(Object selection, Object activePart, Object activeEditor) {
        DBPDataSourceContainer container = null;
        if (activePart instanceof INavigatorModelView) {
            if (selection instanceof IStructuredSelection structured && structured.size() == 1) {
                container = containerOf(NavigatorUtils.getSelectedObject((ISelection) structured));
            }
        } else if (activeEditor instanceof SQLEditor editor) {
            container = editor.getDataSourceContainer();
        } else if (activeEditor instanceof IEditorPart editor
                && editor.getEditorInput() instanceof IDatabaseEditorInput input) {
            container = containerOf(input.getDatabaseObject());
        }
        return container != null && RoutineEditors.isFirebird(container) ? container : null;
    }

    private static DBPDataSourceContainer containerOf(DBSObject object) {
        if (object instanceof DBPDataSourceContainer container) {
            return container;
        }
        return object == null || object.getDataSource() == null ? null : object.getDataSource().getContainer();
    }
}
