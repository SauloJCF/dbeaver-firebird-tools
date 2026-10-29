package dev.fbtools.dbeaver.psqldebug.editor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.editors.StringEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLEditorHandlerOpenEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLNavigatorContext;

import dev.fbtools.dbeaver.psqldebug.debug.SourceId;
import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.StatementLines;

/**
 * Editores SQL do DBeaver (scripts e consoles) de uma conexão Firebird como texto de Rotina. Dois casos:
 * <ul>
 * <li>o console só de leitura com o corpo de uma package, aberto por "Abrir Package": o DBeaver não mostra as
 * packages do Firebird, e o texto do console é a Fonte do banco do corpo, com as posições das rotinas dela;</li>
 * <li>qualquer outro editor SQL, de onde sai um {@code EXECUTE BLOCK}, que não existe no banco: o texto do editor é o
 * código depurado, e as posições são as do documento ({@link RoutineParser#placed}).</li>
 * </ul>
 * Cada editor tem um {@link SourceId}: no console, o da package; nos outros, um {@code EXECUTE_BLOCK} com o nome do
 * editor, dado na primeira vez que ele recebe um breakpoint ou um launch. Só na thread de UI, fora onde dito.
 */
public final class SqlEditors {

    /** Id do editor SQL do DBeaver, que é também o id do site dele. */
    public static final String EDITOR_ID = SQLEditor.class.getName();

    /** Começo de um {@code EXECUTE BLOCK}, depois de espaços e comentários. */
    private static final Pattern BLOCK_START = Pattern.compile(
            "\\A\\s*(?:(?:--[^\\n]*(?:\\n|\\z)|/\\*.*?\\*/)\\s*)*EXECUTE\\s+BLOCK\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Terminador de statement do script ({@code ^} depois de um {@code SET TERM}, por exemplo) no fim do texto
     * selecionado; o {@code ;} a gramática aceita.
     */
    private static final Pattern TRAILING_TERMINATOR = Pattern.compile("[^\\w\\s;)'\"*/]+\\s*\\z");

    /** Texto de cada editor SQL já visto, e dos consoles de package. */
    private static final Map<SQLEditor, SourceId> SOURCES = new HashMap<>();
    private static final Set<IWorkbenchPage> PAGES = new HashSet<>();

    /** Fecha o editor: o texto dele deixa de existir. */
    private static final IPartListener2 CLOSE_LISTENER = new IPartListener2() {
        @Override
        public void partClosed(IWorkbenchPartReference reference) {
            IWorkbenchPart part = reference.getPart(false);
            if (part instanceof SQLEditor editor) {
                SOURCES.remove(editor);
            }
        }
    };

    /**
     * Um {@code EXECUTE BLOCK} no texto do editor.
     *
     * @param text o texto do bloco posto na posição dele no documento ({@link RoutineParser#placed}): as posições do
     *            AST são as do editor
     * @param line linha do documento em que o bloco começa, a partir de 1
     */
    public record Block(String text, int line) {
    }

    private SqlEditors() {
    }

    /** Editor SQL do DBeaver ligado a uma conexão Firebird. De qualquer thread. */
    public static boolean isDebuggable(Object part) {
        if (!(part instanceof SQLEditor editor)) {
            return false;
        }
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        return container != null && RoutineEditors.isFirebird(container);
    }

    /**
     * Texto do editor: o da package, num console aberto por {@link #openPackage}; nos outros, um
     * {@code EXECUTE_BLOCK} com o nome do editor, dado na primeira vez e refeito se a conexão do editor mudar.
     *
     * @return {@code null} se o editor não é editor SQL de conexão Firebird
     */
    public static SourceId sourceOf(IWorkbenchPart part) {
        if (!isDebuggable(part)) {
            return null;
        }
        SQLEditor editor = (SQLEditor) part;
        String dataSourceId = editor.getDataSourceContainer().getId();
        SourceId known = SOURCES.get(editor);
        if (known != null && (known.kind() == SourceId.Kind.PACKAGE || known.dataSourceId().equals(dataSourceId))) {
            return known;
        }
        SourceId created = new SourceId(dataSourceId, SourceId.Kind.EXECUTE_BLOCK, uniqueName(editor, dataSourceId));
        register(editor, created);
        return created;
    }

    /** Nome do editor ({@code Script-3.sql}), com um número se outro editor da conexão já tem esse nome. */
    private static String uniqueName(SQLEditor editor, String dataSourceId) {
        String base = editor.getEditorInput().getName();
        String name = base;
        for (int n = 2; SOURCES.containsValue(new SourceId(dataSourceId, SourceId.Kind.EXECUTE_BLOCK, name)); n++) {
            name = base + " (" + n + ")";
        }
        return name;
    }

    private static void register(SQLEditor editor, SourceId source) {
        SOURCES.put(editor, source);
        IWorkbenchPage page = editor.getSite().getPage();
        if (PAGES.add(page)) {
            page.addPartListener(CLOSE_LISTENER);
        }
    }

    /** @return o texto do editor, ou {@code null} se ele não tem documento */
    public static String text(SQLEditor editor) {
        IDocument document = RoutineEditors.document(editor);
        return document == null ? null : document.get();
    }

    /** O editor é o console do corpo de uma package. */
    static boolean isPackageConsole(IWorkbenchPart part) {
        SourceId source = part instanceof SQLEditor editor ? SOURCES.get(editor) : null;
        return source != null && source.kind() == SourceId.Kind.PACKAGE;
    }

    /**
     * Entrada do editor SQL aberto com o texto, para o debug.ui destacar nele a linha da pausa. De qualquer thread.
     *
     * @return {@code null} se o editor foi fechado (e o debug.ui mostra o editor de "fonte não encontrada")
     */
    public static IEditorInput editorInput(SourceId source) {
        IEditorInput[] input = new IEditorInput[1];
        PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            SQLEditor editor = editorOf(source);
            input[0] = editor == null ? null : editor.getEditorInput();
        });
        return input[0];
    }

    private static SQLEditor editorOf(SourceId source) {
        for (Map.Entry<SQLEditor, SourceId> entry : SOURCES.entrySet()) {
            if (entry.getValue().equals(source) && isOpen(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean isOpen(SQLEditor editor) {
        IWorkbenchPage page = editor.getSite().getPage();
        for (IEditorReference reference : page.getEditorReferences()) {
            if (reference.getPart(false) == editor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mostra o corpo de uma package num console só de leitura da conexão, onde ficam os breakpoints e de onde a rotina
     * sob o cursor é depurada. Se já há console da package com o mesmo texto, ele é ativado; se o corpo mudou no banco,
     * o console antigo é fechado (com os breakpoints dele, cujas linhas eram de outro texto) e um novo é aberto.
     *
     * @param body o corpo como o {@code RoutineSourceLoader} o monta, de que são as posições das rotinas
     */
    public static void openPackage(IWorkbenchWindow window, DBPDataSourceContainer container, String name,
            String body) {
        SourceId source = new SourceId(container.getId(), SourceId.Kind.PACKAGE, name);
        SQLEditor existing = editorOf(source);
        if (existing != null) {
            IDocument document = RoutineEditors.document(existing);
            if (document != null && document.get().equals(body)) {
                existing.getSite().getPage().activate(existing);
                return;
            }
            existing.getSite().getPage().closeEditor(existing, false);
        }
        StringEditorInput input = new StringEditorInput(name + " (corpo da package)", body, true,
                StandardCharsets.UTF_8.name());
        SQLEditor editor = SQLEditorHandlerOpenEditor.openSQLConsole(window, new SQLNavigatorContext(container, null),
                input);
        if (editor != null) {
            register(editor, source);
        }
    }

    /**
     * O {@code EXECUTE BLOCK} selecionado ou, sem seleção, o do cursor, como o DBeaver separa o statement para
     * executá-lo.
     *
     * @return {@code null} se o texto não é um {@code EXECUTE BLOCK}
     */
    public static Block activeBlock(SQLEditor editor) {
        IDocument document = RoutineEditors.document(editor);
        return document == null ? null : block(document, editor.extractActiveQuery());
    }

    /** @param line linha do documento, a partir de 1 */
    private static Block blockAt(SQLEditor editor, IDocument document, int line) throws BadLocationException {
        return block(document, editor.extractQueryAtPos(document.getLineOffset(line - 1)));
    }

    private static Block block(IDocument document, SQLScriptElement element) {
        if (element == null || element.getOffset() < 0 || element.getLength() <= 0) {
            return null;
        }
        try {
            int offset = element.getOffset();
            String text = document.get(offset, element.getLength());
            if (!BLOCK_START.matcher(text).lookingAt()) {
                return null;
            }
            Matcher terminator = TRAILING_TERMINATOR.matcher(text);
            if (terminator.find()) {
                text = text.substring(0, terminator.start());
            }
            int line = document.getLineOfOffset(offset);
            int column = offset - document.getLineOffset(line);
            return new Block(RoutineParser.placed(text, line + 1, column + 1), line + 1);
        } catch (BadLocationException e) {
            return null;
        }
    }

    /**
     * Rotina do console da package em que está o cursor.
     *
     * @return {@code null} se o cursor está fora das rotinas, ou se o editor não é um console de package
     * @throws PsqlSyntaxException se o corpo não é reconhecido
     */
    public static RoutineParser.PackageRoutine packageRoutineAt(SQLEditor editor) {
        IDocument document = RoutineEditors.document(editor);
        if (!isPackageConsole(editor) || document == null
                || !(editor.getSelectionProvider().getSelection() instanceof ITextSelection selection)) {
            return null;
        }
        int line = selection.getStartLine() + 1;
        for (RoutineParser.PackageRoutine routine : RoutineParser.parsePackageBody(document.get())) {
            if (routine.firstLine() <= line && line <= routine.lastLine()) {
                return routine;
            }
        }
        return null;
    }

    /**
     * Linhas com statement do texto em que fica a linha: no console de package, as de todas as rotinas do corpo; nos
     * outros editores, as do {@code EXECUTE BLOCK} que contém a linha.
     *
     * @param line linha do documento, a partir de 1
     * @return {@code null} se a linha não está num {@code EXECUTE BLOCK}
     * @throws PsqlSyntaxException se o texto não é reconhecido
     */
    static NavigableSet<Integer> statementLines(SQLEditor editor, IDocument document, int line) {
        if (isPackageConsole(editor)) {
            NavigableSet<Integer> lines = new TreeSet<>();
            RoutineParser.parsePackageBody(document.get())
                    .forEach(routine -> lines.addAll(StatementLines.of(routine.routine())));
            return lines;
        }
        Block block;
        try {
            block = blockAt(editor, document, line);
        } catch (BadLocationException e) {
            return null;
        }
        return block == null ? null : StatementLines.of(RoutineParser.parse(block.text()));
    }
}
