package dev.fbtools.dbeaver.psqldebug.editor;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.services.IEvaluationService;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.ILazyEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * {@code dev.fbtools.dbeaver.psqldebug.breakpointSource}: o editor é o editor do objeto de uma procedure, function ou trigger de
 * tabela Firebird, ou um editor SQL de conexão Firebird ({@link SqlEditors}); os dois podem receber breakpoints. Liga os comandos de
 * breakpoint e "Depurar Rotina" só nesses editores e mostra "Depurar Rotina" no menu de contexto deles.
 * <p>
 * Lê a entrada atual do editor, e não a variável {@code activeEditorInput}: um editor restaurado ao abrir o DBeaver tem
 * uma entrada provisória até a conexão abrir, e o DBeaver troca a entrada sem avisar o workbench, que continua com a
 * provisória na variável. Quando a entrada de verdade chega, a propriedade é reavaliada.
 */
public final class RoutineEditorTester extends PropertyTester {

    private static final String PROPERTY = "dev.fbtools.dbeaver.psqldebug.breakpointSource";

    /** Editores restaurados à espera da entrada de verdade. */
    private static final Set<IEditorPart> LOADING = Collections.newSetFromMap(new WeakHashMap<>());

    /** @param receiver o editor ativo ({@code activeEditor}) */
    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        if (receiver instanceof SQLEditor) {
            return SqlEditors.isDebuggable(receiver);
        }
        if (!(receiver instanceof IEditorPart editor)) {
            return false;
        }
        IEditorInput input = editor.getEditorInput();
        if (input instanceof ILazyEditorInput) {
            reevaluateWhenLoaded(editor);
            return false;
        }
        return input instanceof IDatabaseEditorInput database
                && RoutineEditors.sourceOf(database.getDatabaseObject()) != null;
    }

    /**
     * O editor do objeto avisa com uma mudança de propriedade quando recria as pastas com a entrada de verdade; aí as
     * expressões que usam a propriedade são avaliadas de novo.
     */
    private static void reevaluateWhenLoaded(IEditorPart editor) {
        if (!LOADING.add(editor)) {
            return;
        }
        editor.addPropertyListener(new IPropertyListener() {
            @Override
            public void propertyChanged(Object source, int propId) {
                if (editor.getEditorInput() instanceof ILazyEditorInput) {
                    return;
                }
                editor.removePropertyListener(this);
                LOADING.remove(editor);
                PlatformUI.getWorkbench().getService(IEvaluationService.class).requestEvaluation(PROPERTY);
            }
        });
    }
}
