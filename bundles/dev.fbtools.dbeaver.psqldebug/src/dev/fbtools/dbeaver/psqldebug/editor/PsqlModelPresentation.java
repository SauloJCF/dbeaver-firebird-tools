package dev.fbtools.dbeaver.psqldebug.editor;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugEditorPresentation;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.debug.ui.IValueDetailListener;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.entity.EntityEditor;

import dev.fbtools.dbeaver.psqldebug.debug.PsqlLineBreakpoint;
import dev.fbtools.dbeaver.psqldebug.debug.SourceId;

/**
 * Apresentação do modelo de debug no debug.ui: o texto dos breakpoints, o detalhe dos valores e o editor da linha da
 * pausa. O elemento de fonte de um stack frame é o objeto do DBeaver ({@code PsqlSourceLocator}): o da Rotina raiz ou
 * o de uma Rotina alcançada por step into, cujo editor é aberto se preciso. A linha é destacada no editor do objeto, na
 * pasta Source, cujo editor de texto o debug.ui acha por {@code getAdapter(ITextEditor.class)}. O texto de um
 * {@code EXECUTE BLOCK} ou do corpo de uma package é o elemento de fonte ({@code SourceId}), e a linha é destacada no
 * editor SQL que o tem. O que não está aqui fica com a apresentação padrão.
 */
public final class PsqlModelPresentation extends LabelProvider
        implements IDebugModelPresentation, IDebugEditorPresentation {

    @Override
    public void setAttribute(String attribute, Object value) {
    }

    @Override
    public Image getImage(Object element) {
        return null;
    }

    @Override
    public String getText(Object element) {
        return element instanceof PsqlLineBreakpoint breakpoint ? breakpoint.label() : null;
    }

    @Override
    public void computeDetail(IValue value, IValueDetailListener listener) {
        String detail;
        try {
            detail = value.getValueString();
        } catch (DebugException e) {
            detail = e.getMessage();
        }
        listener.detailComputed(value, detail);
    }

    /**
     * O objeto do DBeaver abre no editor dele; o texto de um {@code EXECUTE BLOCK} ou do corpo de uma package é o do
     * editor SQL de onde ele saiu ({@link SqlEditors}). Chamado fora da thread de UI.
     */
    @Override
    public IEditorInput getEditorInput(Object element) {
        if (element instanceof SourceId source) {
            return SqlEditors.editorInput(source);
        }
        return element instanceof DBSObject object ? RoutineEditors.showSource(object) : null;
    }

    @Override
    public String getEditorId(IEditorInput input, Object element) {
        return element instanceof SourceId ? SqlEditors.EDITOR_ID : EntityEditor.class.getName();
    }

    /**
     * Antes de o debug.ui destacar a linha: põe o editor na pasta Source, onde fica o editor de texto, e os avisos das
     * chamadas executadas no servidor ({@link ServerCallAnnotations}). O destaque em si é o padrão do debug.ui
     * ({@code false}), que também o tira quando a execução continua. Um editor de objeto com alterações não salvas
     * mostra um texto que não é o depurado: a linha de status avisa. No editor SQL, o texto do editor é o código.
     */
    @Override
    public boolean addAnnotations(IEditorPart editorPart, IStackFrame frame) {
        RoutineEditors.showSourceFolder(editorPart);
        ServerCallAnnotations.show(editorPart, frame);
        if (editorPart.isDirty() && editorPart.getEditorInput() instanceof IDatabaseEditorInput) {
            ITextEditor text = RoutineEditors.sourceText(editorPart);
            RoutineEditors.status(text != null ? text : editorPart, "O editor tem alterações não salvas: a linha "
                    + "destacada é a da Fonte do banco, que é o código depurado.", false);
        }
        redisplayWhenLoaded(editorPart, frame);
        return false;
    }

    /**
     * Um editor de objeto recém-aberto mostra na pasta Source um texto provisório ("Loading..."), lê a Fonte num job e
     * aí troca a entrada do editor de texto, o que tira o destaque posto pelo debug.ui. Sem a linha da pausa no
     * documento, a linha é mostrada de novo quando a entrada for trocada, se o frame ainda é o selecionado na view
     * Debug.
     */
    private static void redisplayWhenLoaded(IEditorPart editorPart, IStackFrame frame) {
        ITextEditor text = RoutineEditors.sourceText(editorPart);
        IDocument document = text == null ? null : RoutineEditors.document(text);
        try {
            if (document == null || document.getNumberOfLines() >= frame.getLineNumber()) {
                return;
            }
        } catch (DebugException e) {
            return;
        }
        text.addPropertyListener(new IPropertyListener() {
            @Override
            public void propertyChanged(Object source, int propId) {
                if (propId != IEditorPart.PROP_INPUT) {
                    return;
                }
                text.removePropertyListener(this);
                Display.getCurrent().asyncExec(() -> {
                    if (DebugUITools.getDebugContext() == frame && !frame.isTerminated()) {
                        DebugUITools.displaySource(
                                DebugUITools.lookupSource(frame, frame.getLaunch().getSourceLocator()),
                                text.getSite().getPage());
                    }
                });
            }
        });
    }

    @Override
    public void removeAnnotations(IEditorPart editorPart, IThread thread) {
    }
}
