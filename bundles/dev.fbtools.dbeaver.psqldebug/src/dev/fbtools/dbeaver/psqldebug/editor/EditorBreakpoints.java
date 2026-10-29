package dev.fbtools.dbeaver.psqldebug.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointListener;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.MarkerAnnotation;

import dev.fbtools.dbeaver.psqldebug.debug.PsqlLineBreakpoint;
import dev.fbtools.dbeaver.psqldebug.debug.SourceId;
import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.StatementLines;

/**
 * Breakpoints de linha no editor de Rotina do DBeaver: alternados no texto da pasta Source, com a marca no ruler, e
 * removidos quando o editor fecha (não persistentes, ADR 0004). Um breakpoint numa linha sem statement vai para o
 * próximo statement ({@link StatementLines}); sem nenhum depois, é recusado. No editor SQL de uma conexão Firebird
 * ({@link SqlEditors}), valem dentro de um {@code EXECUTE BLOCK} e no console do corpo de uma package.
 * <p>
 * O editor do DBeaver não é de arquivo, e o modelo de anotações dele não acompanha markers: a marca do ruler é posta e
 * tirada à mão, e posta de novo quando o editor recarrega o texto. Ela acompanha o texto editado; a linha do
 * breakpoint passa a ser a da marca no launch ({@link #prepareLaunch}). Só na thread de UI.
 */
public final class EditorBreakpoints {

    private static final ILog LOG = ILog.of(EditorBreakpoints.class);

    /** Um breakpoint no editor: o editor do objeto, o de texto da pasta Source e a marca no ruler. */
    private record Placement(IEditorPart editor, ITextEditor text, MarkerAnnotation annotation) {
    }

    private static final Map<PsqlLineBreakpoint, Placement> PLACEMENTS = new HashMap<>();
    private static final Set<IWorkbenchPage> PAGES = new HashSet<>();
    private static final Set<ITextEditor> WATCHED = new HashSet<>();

    /** Fecha o editor do objeto: os breakpoints dele somem. */
    private static final IPartListener2 CLOSE_LISTENER = new IPartListener2() {
        @Override
        public void partClosed(IWorkbenchPartReference reference) {
            IWorkbenchPart part = reference.getPart(false);
            List<PsqlLineBreakpoint> closed = new ArrayList<>();
            PLACEMENTS.forEach((breakpoint, placement) -> {
                if (placement.editor() == part) {
                    closed.add(breakpoint);
                }
            });
            closed.forEach(EditorBreakpoints::remove);
            WATCHED.removeIf(text -> RoutineEditors.objectEditor(text) == part);
        }
    };

    /** Breakpoint removido por fora (view Breakpoints), de qualquer thread: a marca sai do ruler. */
    private static final IBreakpointListener REMOVAL_LISTENER = new IBreakpointListener() {
        @Override
        public void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta delta) {
            if (breakpoint instanceof PsqlLineBreakpoint line) {
                PlatformUI.getWorkbench().getDisplay().asyncExec(() -> forget(line));
            }
        }

        @Override
        public void breakpointAdded(IBreakpoint breakpoint) {
        }

        @Override
        public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta delta) {
        }
    };

    static {
        breakpointManager().addBreakpointListener(REMOVAL_LISTENER);
    }

    private EditorBreakpoints() {
    }

    /**
     * Alterna o breakpoint de uma linha do editor de Rotina. Se a linha não tem statement, vale a do próximo
     * statement (que pode já ter breakpoint, e aí ele sai).
     *
     * @param part o editor do objeto ou o editor de texto da pasta Source
     * @param documentLine linha do documento, a partir de 0; negativa se não se sabe a linha (nada é feito)
     */
    static void toggle(IWorkbenchPart part, int documentLine) {
        ITextEditor text = RoutineEditors.sourceText(part);
        IEditorPart editor = RoutineEditors.objectEditor(text);
        SourceId source = RoutineEditors.sourceOf(editor);
        IDocument document = text == null ? null : RoutineEditors.document(text);
        if (source == null || document == null) {
            RoutineEditors.status(part, "Breakpoint só no texto (pasta Source) de procedure ou function Firebird, "
                    + "ou num editor SQL de conexão Firebird.", true);
            return;
        }
        if (documentLine < 0) {
            return;
        }
        int clicked = documentLine + 1;
        PsqlLineBreakpoint existing = at(editor, clicked);
        if (existing != null) {
            remove(existing);
            return;
        }
        Integer line = statementLine(editor, text, document, clicked);
        if (line == null) {
            return;
        }
        existing = at(editor, line);
        if (existing != null) {
            remove(existing);
            return;
        }
        try {
            add(editor, text, source, line);
        } catch (CoreException e) {
            LOG.error("Falha ao criar o breakpoint", e);
            RoutineEditors.status(text, "Falha ao criar o breakpoint: " + e.getMessage(), true);
            return;
        }
        if (line != clicked) {
            RoutineEditors.status(text, "Breakpoint na linha " + line + ", a do próximo statement.", false);
        }
    }

    /**
     * Linha do statement que recebe o breakpoint: a clicada, se tem statement, ou a do próximo. Se o texto do editor
     * não é reconhecido (alteração não salva, por exemplo), fica a clicada; o launch acerta a linha pela Fonte do
     * banco ({@link #prepareLaunch}). Num editor SQL, o texto é o do {@code EXECUTE BLOCK} que contém a linha, ou o
     * corpo da package do console.
     *
     * @return {@code null} se não há statement na linha nem depois dela, ou se a linha não está num
     *         {@code EXECUTE BLOCK}
     */
    private static Integer statementLine(IEditorPart editor, ITextEditor text, IDocument document, int clicked) {
        NavigableSet<Integer> lines;
        try {
            lines = RoutineEditors.statementLines(editor, document, clicked);
        } catch (PsqlSyntaxException e) {
            RoutineEditors.status(text, "Texto não reconhecido (" + e.getMessage()
                    + "); o breakpoint fica na linha e é conferido no launch.", false);
            return clicked;
        }
        if (lines == null) {
            RoutineEditors.status(text, "No editor SQL, breakpoint só dentro de um EXECUTE BLOCK.", true);
            return null;
        }
        Integer line = lines.ceiling(clicked);
        if (line == null) {
            RoutineEditors.status(text, "Não há statement nesta linha nem depois dela.", true);
        }
        return line;
    }

    private static void add(IEditorPart editor, ITextEditor text, SourceId source, int line) throws CoreException {
        PsqlLineBreakpoint breakpoint = new PsqlLineBreakpoint(source, line);
        Placement placement = new Placement(editor, text, new MarkerAnnotation(breakpoint.getMarker()));
        PLACEMENTS.put(breakpoint, placement);
        annotate(placement, line);
        watch(editor, text);
        breakpointManager().addBreakpoint(breakpoint);
    }

    private static void remove(PsqlLineBreakpoint breakpoint) {
        forget(breakpoint);
        try {
            breakpointManager().removeBreakpoint(breakpoint, true);
        } catch (CoreException e) {
            LOG.warn("Falha ao remover o breakpoint", e);
        }
    }

    /** Tira a marca do ruler; o breakpoint já saiu ou vai sair do gerenciador. */
    private static void forget(PsqlLineBreakpoint breakpoint) {
        Placement placement = PLACEMENTS.remove(breakpoint);
        if (placement != null) {
            unannotate(placement);
        }
    }

    /** Breakpoint do editor numa linha: a da marca no ruler, que acompanha o texto. */
    private static PsqlLineBreakpoint at(IEditorPart editor, int line) {
        for (Map.Entry<PsqlLineBreakpoint, Placement> entry : PLACEMENTS.entrySet()) {
            if (entry.getValue().editor() == editor && currentLine(entry.getKey(), entry.getValue()) == line) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Antes do launch da Rotina, acerta os breakpoints: a linha de cada um passa a ser a da marca no ruler, que
     * acompanhou o texto editado e salvo desde que ele foi posto. Nos do texto da Rotina, um breakpoint numa linha sem
     * statement vai para o próximo statement (sem nenhum depois, fica onde está e não para). Os das outras Rotinas, que
     * a execução pode chamar, só seguem a marca: a Fonte do banco delas só é lida na chamada.
     *
     * @param source o texto da Rotina: a Fonte do banco ou, num {@code EXECUTE BLOCK}, o editor SQL
     * @param lines linhas com statement do texto: as da Rotina ou, no corpo de uma package, as de todas as rotinas
     *            dele ({@link StatementLines})
     * @param firstLine linha em que a Rotina começa no texto: 1, ou a do {@code EXECUTE BLOCK} no editor SQL, onde os
     *            breakpoints de cima são de outro bloco e ficam onde estão
     */
    public static void prepareLaunch(SourceId source, NavigableSet<Integer> lines, int firstLine) {
        for (Map.Entry<PsqlLineBreakpoint, Placement> entry : PLACEMENTS.entrySet()) {
            PsqlLineBreakpoint breakpoint = entry.getKey();
            Placement placement = entry.getValue();
            int current = currentLine(breakpoint, placement);
            if (current < 1) {
                continue; // o marker foi removido
            }
            Integer statement = source.equals(breakpoint.source()) && current >= firstLine ? lines.ceiling(current)
                    : null;
            int line = statement == null ? current : statement;
            try {
                if (line != breakpoint.getLineNumber()) {
                    breakpoint.moveTo(line);
                }
            } catch (CoreException e) {
                LOG.warn("Falha ao acertar a linha do breakpoint", e);
            }
            if (line != current) {
                unannotate(placement);
                annotate(placement, line);
            }
        }
    }

    /** Linha da marca no ruler; sem marca (o editor recarregou o texto), a do breakpoint. */
    private static int currentLine(PsqlLineBreakpoint breakpoint, Placement placement) {
        IAnnotationModel model = RoutineEditors.annotationModel(placement.text());
        IDocument document = RoutineEditors.document(placement.text());
        Position position = model == null ? null : model.getPosition(placement.annotation());
        if (position != null && !position.isDeleted() && document != null) {
            try {
                return document.getLineOfOffset(position.getOffset()) + 1;
            } catch (BadLocationException e) {
                // a marca ficou fora do texto: vale a linha do breakpoint
            }
        }
        try {
            return breakpoint.getLineNumber();
        } catch (CoreException e) {
            return -1;
        }
    }

    private static void annotate(Placement placement, int line) {
        IAnnotationModel model = RoutineEditors.annotationModel(placement.text());
        IDocument document = RoutineEditors.document(placement.text());
        if (model == null || document == null || line < 1 || line > document.getNumberOfLines()) {
            return;
        }
        try {
            model.addAnnotation(placement.annotation(),
                    new Position(document.getLineOffset(line - 1), document.getLineLength(line - 1)));
        } catch (BadLocationException e) {
            // linha fora do texto: o breakpoint fica sem marca
        }
    }

    private static void unannotate(Placement placement) {
        IAnnotationModel model = RoutineEditors.annotationModel(placement.text());
        if (model != null) {
            model.removeAnnotation(placement.annotation());
        }
    }

    /** Passa a acompanhar o fechamento do editor e a recarga do texto dele. */
    private static void watch(IEditorPart editor, ITextEditor text) {
        IWorkbenchPage page = editor.getSite().getPage();
        if (PAGES.add(page)) {
            page.addPartListener(CLOSE_LISTENER);
        }
        ITextViewer viewer = text.getAdapter(ITextViewer.class);
        if (viewer != null && WATCHED.add(text)) {
            viewer.addTextInputListener(new ITextInputListener() {
                @Override
                public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput) {
                }

                /** O editor recarregou o texto (refresh do objeto): novo modelo de anotações, sem as marcas. */
                @Override
                public void inputDocumentChanged(IDocument oldInput, IDocument newInput) {
                    if (newInput != null) {
                        reannotate(text);
                    }
                }
            });
        }
    }

    private static void reannotate(ITextEditor text) {
        PLACEMENTS.forEach((breakpoint, placement) -> {
            if (placement.text() == text) {
                unannotate(placement);
                try {
                    annotate(placement, breakpoint.getLineNumber());
                } catch (CoreException e) {
                    // o breakpoint foi removido
                }
            }
        });
    }

    private static IBreakpointManager breakpointManager() {
        return DebugPlugin.getDefault().getBreakpointManager();
    }
}
