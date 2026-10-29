package dev.fbtools.dbeaver.psqldebug.editor;

import java.util.Collection;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;

import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorSite;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IEditorStatusLine;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.ext.generic.model.GenericStructContainer;
import org.jkiss.dbeaver.ext.generic.model.GenericTableBase;
import org.jkiss.dbeaver.ext.generic.model.GenericTrigger;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.folders.ITabbedFolderContainer;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

import dev.fbtools.dbeaver.psqldebug.debug.SourceId;
import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.StatementLines;

/**
 * Editor do objeto do DBeaver de uma Rotina Firebird e o editor de texto da pasta Source dele, onde ficam os
 * breakpoints e a linha da pausa; também o editor SQL de uma conexão Firebird, que faz o mesmo papel para um
 * {@code EXECUTE BLOCK} e para o corpo de uma package ({@link SqlEditors}). O plugin do Firebird do DBeaver não exporta
 * as classes dele: a Rotina é a procedure ou a trigger do plugin genérico, e a conexão Firebird é reconhecida pelo
 * provedor do driver. Só na thread de UI, fora onde dito.
 */
public final class RoutineEditors {

    private static final ILog LOG = ILog.of(RoutineEditors.class);

    /** Provedor de conexão do plugin {@code org.jkiss.dbeaver.ext.firebird}. */
    private static final String FIREBIRD_PROVIDER = "jaybird";

    /**
     * Pasta Source do editor de procedure e function do plugin genérico do DBeaver, que o do Firebird usa. É também o
     * id do site do editor de texto dela.
     */
    static final String SOURCE_FOLDER = "generic.source.view";

    private RoutineEditors() {
    }

    /** O objeto (procedure, function, trigger) é de uma conexão Firebird. */
    public static boolean isFirebird(DBSObject object) {
        DBPDataSourceContainer container = object.getDataSource() == null ? null
                : object.getDataSource().getContainer();
        return container != null && isFirebird(container);
    }

    /**
     * Trigger de tabela Firebird, a única que se depura (Trigger isolada). A do plugin do Firebird é uma
     * {@code GenericTrigger} cujo dono é a tabela; o de uma trigger de banco é o banco.
     */
    public static boolean isTableTrigger(DBSObject object) {
        return object instanceof GenericTrigger<?> trigger && trigger.getParentObject() instanceof GenericTableBase
                && isFirebird(trigger);
    }

    /** A conexão do DBeaver é Firebird. */
    public static boolean isFirebird(DBPDataSourceContainer container) {
        return FIREBIRD_PROVIDER.equals(container.getDriver().getProviderId());
    }

    /**
     * @return o texto da Rotina, ou {@code null} se o objeto não é procedure, function nem trigger de tabela Firebird
     */
    public static SourceId sourceOf(DBSObject object) {
        if (isTableTrigger(object)) {
            return new SourceId(object.getDataSource().getContainer().getId(), SourceId.Kind.TRIGGER,
                    object.getName());
        }
        if (!(object instanceof GenericProcedure procedure) || !isFirebird(procedure)) {
            return null;
        }
        String dataSourceId = procedure.getDataSource().getContainer().getId();
        if (procedure.getPackage() != null) {
            return new SourceId(dataSourceId, SourceId.Kind.PACKAGE, procedure.getPackage().getName());
        }
        SourceId.Kind kind = procedure.getProcedureType() == DBSProcedureType.FUNCTION ? SourceId.Kind.FUNCTION
                : SourceId.Kind.PROCEDURE;
        return new SourceId(dataSourceId, kind, procedure.getName());
    }

    /**
     * Objeto do DBeaver de um texto de Rotina da conexão: uma procedure ou function fora de package, achada pelo nome
     * e pelo tipo. Rotina de package não tem objeto: o DBeaver não mostra as packages do Firebird, e o texto delas fica
     * no console aberto por "Abrir Package" ({@link SqlEditors}). Trigger não é procurada: nenhuma é alcançada por step
     * into, e a do launch já vem do DBeaver. De qualquer thread: o debug.ui chama fora da thread de UI, e a busca pode
     * ler os metadados do DBeaver.
     *
     * @return {@code null} se não há objeto para o texto
     */
    public static DBSObject routine(DBPDataSourceContainer container, SourceId source) {
        DBSProcedureType type = switch (source.kind()) {
            case PROCEDURE -> DBSProcedureType.PROCEDURE;
            case FUNCTION -> DBSProcedureType.FUNCTION;
            case PACKAGE, TRIGGER, EXECUTE_BLOCK -> null;
        };
        if (type == null || !source.dataSourceId().equals(container.getId())
                || !(container.getDataSource() instanceof GenericStructContainer structure)) {
            return null;
        }
        try {
            Collection<? extends GenericProcedure> procedures = structure.getProcedures(new VoidProgressMonitor());
            for (GenericProcedure procedure : procedures == null ? List.<GenericProcedure>of() : procedures) {
                if (procedure.getName().equals(source.name()) && procedure.getProcedureType() == type
                        && procedure.getPackage() == null) {
                    return procedure;
                }
            }
        } catch (DBException e) {
            LOG.warn("Falha ao procurar " + source.name() + " no DBeaver", e);
        }
        return null;
    }

    /** @return o texto da Rotina do editor do objeto ou do editor SQL ({@link SqlEditors#sourceOf}), ou {@code null} */
    static SourceId sourceOf(IEditorPart editor) {
        if (editor instanceof SQLEditor) {
            return SqlEditors.sourceOf(editor);
        }
        return editor != null && editor.getEditorInput() instanceof IDatabaseEditorInput input
                ? sourceOf(input.getDatabaseObject()) : null;
    }

    /**
     * Linhas com statement do texto do editor em que fica a linha: a Rotina do editor do objeto, ou o texto do editor
     * SQL ({@link SqlEditors#statementLines}).
     *
     * @param editor o editor do objeto ou o editor SQL
     * @param line linha do documento, a partir de 1
     * @return {@code null} se a linha não está num texto de Rotina
     * @throws PsqlSyntaxException se o texto não é reconhecido
     */
    static NavigableSet<Integer> statementLines(IEditorPart editor, IDocument document, int line) {
        if (editor instanceof SQLEditor sql) {
            return SqlEditors.statementLines(sql, document, line);
        }
        return StatementLines.of(RoutineParser.parse(document.get()));
    }

    /** Editor do objeto: a própria parte ou, num editor de texto de uma pasta, o editor que a contém. */
    static IEditorPart objectEditor(IWorkbenchPart part) {
        if (part != null && part.getSite() instanceof MultiPageEditorSite site) {
            return site.getMultiPageEditor();
        }
        return part instanceof IEditorPart editor ? editor : null;
    }

    /**
     * Editor de texto da pasta Source: a própria parte, se é ele, ou o da pasta ativa do editor do objeto, que o
     * repassa pelo {@code getAdapter}. Num editor SQL de conexão Firebird, ele mesmo.
     *
     * @return {@code null} se a pasta ativa não é a Source
     */
    static ITextEditor sourceText(IWorkbenchPart part) {
        if (SqlEditors.isDebuggable(part)) {
            return (ITextEditor) part;
        }
        ITextEditor text = part instanceof ITextEditor editor ? editor
                : part == null ? null : part.getAdapter(ITextEditor.class);
        return text != null && text.getSite() != null && SOURCE_FOLDER.equals(text.getSite().getId()) ? text : null;
    }

    static IDocument document(ITextEditor text) {
        IDocumentProvider provider = text.getDocumentProvider();
        return provider == null ? null : provider.getDocument(text.getEditorInput());
    }

    static IAnnotationModel annotationModel(ITextEditor text) {
        IDocumentProvider provider = text.getDocumentProvider();
        return provider == null ? null : provider.getAnnotationModel(text.getEditorInput());
    }

    /**
     * Editor do objeto aberto para a Rotina, em qualquer janela.
     *
     * @param restore carregar o editor de uma página que ainda não o mostrou
     */
    static IEditorPart findEditor(DBSObject object, boolean restore) {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    if (isEditorOf(reference, object)) {
                        return reference.getEditor(restore);
                    }
                }
            }
        }
        return null;
    }

    private static boolean isEditorOf(IEditorReference reference, DBSObject object) {
        try {
            return reference.getEditorInput() instanceof IDatabaseEditorInput input
                    && Objects.equals(input.getDatabaseObject(), object);
        } catch (PartInitException e) {
            return false;
        }
    }

    /**
     * Editor do objeto da Rotina, aberto se preciso já na pasta Source, para o debug.ui destacar nele a linha da pausa.
     * Um editor já aberto não é recarregado (o que tiraria do ruler as marcas dos breakpoints); a pasta Source é
     * escolhida depois, por {@link #showSourceFolder}. De qualquer thread.
     *
     * @return a entrada do editor, ou {@code null} se não deu para abri-lo
     */
    static IEditorInput showSource(DBSObject object) {
        IEditorInput[] input = new IEditorInput[1];
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            IEditorPart editor = findEditor(object, true);
            if (editor == null) {
                DBNDatabaseNode node = DBNUtils.getNodeByObject(object);
                editor = node == null ? null
                        : NavigatorHandlerObjectOpen.openEntityEditor(node, null, SOURCE_FOLDER, null,
                                UIUtils.getActiveWorkbenchWindow(), false);
            }
            input[0] = editor == null ? null : editor.getEditorInput();
        });
        return input[0];
    }

    /** Passa o editor do objeto para a pasta Source. */
    static void showSourceFolder(IEditorPart editor) {
        if (editor instanceof ITabbedFolderContainer folders) {
            folders.switchFolder(SOURCE_FOLDER);
        }
    }

    /**
     * Texto do editor aberto da Rotina, com a pasta Source ativa.
     *
     * @return {@code null} se não há editor aberto ou se a pasta ativa é outra
     */
    public static String openText(DBSObject object) {
        ITextEditor text = sourceText(findEditor(object, false));
        IDocument document = text == null ? null : document(text);
        return document == null ? null : document.get();
    }

    /** Mensagem na linha de status da janela, com bipe se é erro. */
    static void status(IWorkbenchPart part, String message, boolean error) {
        IEditorStatusLine statusLine = part == null ? null : part.getAdapter(IEditorStatusLine.class);
        if (statusLine != null) {
            statusLine.setMessage(error, message, null);
        }
        if (error) {
            Display.getCurrent().beep();
        }
    }
}
