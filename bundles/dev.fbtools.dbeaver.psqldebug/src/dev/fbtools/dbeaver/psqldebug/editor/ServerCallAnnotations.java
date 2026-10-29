package dev.fbtools.dbeaver.psqldebug.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import dev.fbtools.dbeaver.psqldebug.debug.ServerCallWarnings;
import dev.fbtools.dbeaver.psqldebug.interp.ServerCalls;
import dev.fbtools.dbeaver.psqldebug.psql.CallSites;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;

/**
 * Aviso "executado no servidor" no editor da Rotina durante a Sessão de debug: uma anotação (tipo
 * {@value #TYPE}, declarado no {@code plugin.xml}) no nome de cada chamada sem step into do texto, com o ícone de
 * informação no ruler. É posta quando o debug.ui mostra um frame do texto no editor, e tirada quando a sessão termina.
 * <p>
 * O hover do editor SQL do DBeaver só mostra as anotações dele (erros de sintaxe e de semântica), e não o texto desta.
 * O texto do aviso aparece também no nome do stack frame na view Debug, quando a execução para na linha. Só na thread
 * de UI.
 */
final class ServerCallAnnotations {

    /** Tipo da anotação, com a apresentação no {@code plugin.xml} ({@code markerAnnotationSpecification}). */
    static final String TYPE = "dev.fbtools.dbeaver.psqldebug.serverCall";

    /** Um aviso posto: em que sessão, em que editor de texto, em que chamada. */
    private record Shown(IDebugTarget target, ITextEditor text, SourcePosition position, Annotation annotation) {
    }

    private static final List<Shown> SHOWN = new ArrayList<>();

    static {
        DebugPlugin.getDefault().addDebugEventListener(events -> {
            for (DebugEvent event : events) {
                if (event.getKind() == DebugEvent.TERMINATE && event.getSource() instanceof IDebugTarget target) {
                    PlatformUI.getWorkbench().getDisplay().asyncExec(() -> removeAll(target));
                }
            }
        });
    }

    private ServerCallAnnotations() {
    }

    /**
     * Põe no editor os avisos do texto do frame que ainda não estão nele (o editor pode ter recarregado o texto, que
     * vem sem as anotações). O editor já está na pasta Source.
     */
    static void show(IEditorPart editor, IStackFrame frame) {
        ServerCallWarnings warnings = frame.getAdapter(ServerCallWarnings.class);
        ITextEditor text = RoutineEditors.sourceText(editor);
        IDebugTarget target = frame.getDebugTarget();
        if (warnings == null || warnings.calls().isEmpty() || text == null || target.isTerminated()) {
            return;
        }
        IAnnotationModel model = RoutineEditors.annotationModel(text);
        IDocument document = RoutineEditors.document(text);
        if (model == null || document == null) {
            return;
        }
        SHOWN.removeIf(shown -> shown.text() == text && model.getPosition(shown.annotation()) == null);
        for (ServerCalls.Call call : warnings.calls()) {
            SourcePosition position = call.site().position();
            boolean present = SHOWN.stream().anyMatch(shown -> shown.target() == target && shown.text() == text
                    && shown.position().equals(position));
            Position place = present ? null : place(document, call);
            if (place != null) {
                Annotation annotation = new Annotation(TYPE, false, call.warning());
                model.addAnnotation(annotation, place);
                SHOWN.add(new Shown(target, text, position, annotation));
            }
        }
    }

    /**
     * Trecho do nome chamado: o identificador na posição da chamada ({@code F}, {@code PKG.F}). Num
     * {@code EXECUTE PROCEDURE}, cuja posição é a do statement, o nome procurado na linha a partir dela.
     *
     * @return {@code null} se a posição está fora do texto do editor
     */
    private static Position place(IDocument document, ServerCalls.Call call) {
        SourcePosition position = call.site().position();
        try {
            if (position.line() < 1 || position.line() > document.getNumberOfLines()) {
                return null;
            }
            IRegion line = document.getLineInformation(position.line() - 1);
            String lineText = document.get(line.getOffset(), line.getLength());
            int start = position.column() - 1;
            if (start < 0 || start >= lineText.length()) {
                return null;
            }
            if (call.site().form() == CallSites.Form.EXECUTE_PROCEDURE) {
                String name = call.site().name();
                int found = lineText.toUpperCase(Locale.ROOT).indexOf(name.toUpperCase(Locale.ROOT), start);
                if (found >= 0) {
                    return new Position(line.getOffset() + found, name.length());
                }
            }
            int end = start;
            while (end < lineText.length() && isNamePart(lineText.charAt(end))) {
                end++;
            }
            return new Position(line.getOffset() + start, Math.max(end - start, 1));
        } catch (BadLocationException e) {
            return null;
        }
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c == '"';
    }

    /** Fim da sessão: os avisos dela saem dos editores. */
    private static void removeAll(IDebugTarget target) {
        SHOWN.removeIf(shown -> {
            if (shown.target() != target) {
                return false;
            }
            IAnnotationModel model = RoutineEditors.annotationModel(shown.text());
            if (model != null) {
                model.removeAnnotation(shown.annotation());
            }
            return true;
        });
    }
}
