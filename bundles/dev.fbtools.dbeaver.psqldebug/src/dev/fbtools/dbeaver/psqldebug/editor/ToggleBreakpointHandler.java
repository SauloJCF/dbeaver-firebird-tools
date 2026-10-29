package dev.fbtools.dbeaver.psqldebug.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Ctrl+Shift+B ({@code org.eclipse.debug.ui.commands.ToggleBreakpoint}) no editor de Rotina: alterna o breakpoint da
 * linha do cursor. O debug.ui só liga esse comando a um handler pelo {@code breakpointActionSet}, que o DBeaver não
 * exibe.
 */
public final class ToggleBreakpointHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        ITextEditor text = RoutineEditors.sourceText(part);
        int line = text != null && text.getSelectionProvider().getSelection() instanceof ITextSelection selection
                ? selection.getStartLine() : -1;
        EditorBreakpoints.toggle(part, line);
        return null;
    }
}
