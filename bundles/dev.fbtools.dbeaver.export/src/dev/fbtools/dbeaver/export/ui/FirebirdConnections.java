package dev.fbtools.dbeaver.export.ui;

import java.sql.Connection;
import java.sql.SQLException;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.navigator.INavigatorModelView;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

/** Acha a conexão Firebird do contexto (navegador ou editor ativo) e abre um contexto JDBC isolado nela. */
final class FirebirdConnections {

    // O provider do driver Jaybird no DBeaver; o depurador usa o mesmo valor, mas os plugins não compartilham código (ADR 0006).
    private static final String FIREBIRD_PROVIDER = "jaybird";
    private static final String CONTEXT_NAME = "Firebird Metadata Export";

    private FirebirdConnections() {
    }

    /** @return a conexão Firebird da seleção do navegador ou do editor ativo; {@code null} se não há */
    static DBPDataSourceContainer find(Object selection, Object activePart, Object activeEditor) {
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
        return container != null && FIREBIRD_PROVIDER.equals(container.getDriver().getProviderId()) ? container : null;
    }

    /** Contexto isolado, fora de qualquer editor; quem chama fecha. */
    static DBCExecutionContext open(DBRProgressMonitor monitor, DBPDataSourceContainer container)
            throws DBException {
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            throw new DBException("a conexão " + container.getName() + " não está aberta");
        }
        return dataSource.getDefaultInstance().openIsolatedContext(monitor, CONTEXT_NAME, null);
    }

    /** Fecha o contexto isolado; uma falha ao fechar não muda o resultado da extração. */
    static void close(DBCExecutionContext context) {
        try {
            context.close();
        } catch (DBException | RuntimeException e) {
            // nada a fazer: o script já foi gravado ou a falha original já foi reportada
        }
    }

    static Connection jdbc(DBRProgressMonitor monitor, DBCExecutionContext context) throws SQLException {
        Connection connection = ((JDBCExecutionContext) context).getConnection(monitor);
        connection.setAutoCommit(true);
        return connection;
    }

    private static DBPDataSourceContainer containerOf(DBSObject object) {
        if (object instanceof DBPDataSourceContainer container) {
            return container;
        }
        return object == null || object.getDataSource() == null ? null : object.getDataSource().getContainer();
    }
}
