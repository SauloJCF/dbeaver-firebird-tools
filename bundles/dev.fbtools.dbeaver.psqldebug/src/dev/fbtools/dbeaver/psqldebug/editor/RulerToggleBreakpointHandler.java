package dev.fbtools.dbeaver.psqldebug.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.text.source.IVerticalRulerInfo;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Duplo-clique no ruler do editor de Rotina e item "Alternar breakpoint" do menu de contexto dele: alterna o
 * breakpoint da linha clicada. O duplo-clique chega pelo {@code editorActions} do {@code plugin.xml}: o editor de
 * texto aninhado do DBeaver procura a ação {@code RulerDoubleClick} pelo id do site dele, que é o da pasta Source.
 */
public final class RulerToggleBreakpointHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        ITextEditor text = RoutineEditors.sourceText(part);
        IVerticalRulerInfo ruler = text == null ? null : text.getAdapter(IVerticalRulerInfo.class);
        EditorBreakpoints.toggle(part, ruler == null ? -1 : ruler.getLineOfLastMouseButtonActivity());
        return null;
    }
}
