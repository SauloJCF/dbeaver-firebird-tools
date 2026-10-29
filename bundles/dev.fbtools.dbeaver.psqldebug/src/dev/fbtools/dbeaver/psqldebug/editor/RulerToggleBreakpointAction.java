package dev.fbtools.dbeaver.psqldebug.editor;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.text.source.IVerticalRulerInfo;
import org.eclipse.ui.texteditor.AbstractRulerActionDelegate;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Duplo-clique no ruler do editor SQL do DBeaver: alterna o breakpoint da linha clicada, se o editor é de conexão
 * Firebird; nos outros, não faz nada. É a classe da ação {@code RulerDoubleClick} que o {@code editorActions} do
 * {@code plugin.xml} dá ao editor SQL, que é um editor de primeiro nível.
 */
public final class RulerToggleBreakpointAction extends AbstractRulerActionDelegate {

    @Override
    protected IAction createAction(ITextEditor editor, IVerticalRulerInfo rulerInfo) {
        return new Action() {
            @Override
            public void run() {
                if (SqlEditors.isDebuggable(editor)) {
                    EditorBreakpoints.toggle(editor, rulerInfo.getLineOfLastMouseButtonActivity());
                }
            }
        };
    }
}
