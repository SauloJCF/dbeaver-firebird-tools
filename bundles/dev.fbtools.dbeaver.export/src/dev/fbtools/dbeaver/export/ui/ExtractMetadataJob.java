package dev.fbtools.dbeaver.export.ui;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWT;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.EditorUtils;

import dev.fbtools.dbeaver.export.extract.DatabaseInfo;
import dev.fbtools.dbeaver.export.extract.ExtractionCancelledException;
import dev.fbtools.dbeaver.export.extract.ExtractionFile;
import dev.fbtools.dbeaver.export.extract.ExtractionProgress;
import dev.fbtools.dbeaver.export.extract.Selection;

/**
 * Extração em background, com progresso no monitor e cancelamento. O contexto JDBC é isolado e sempre fechado; ao
 * cancelar, o arquivo parcial é removido ({@link ExtractionFile}).
 */
final class ExtractMetadataJob extends AbstractJob {

    private static final String TITLE = "Extract Metadata";

    private final DBPDataSourceContainer container;
    private final Selection selection;
    private final Path file;
    private final OutputPage.Encoding encoding;
    private final boolean openWhenDone;

    ExtractMetadataJob(DBPDataSourceContainer container, Selection selection, Path file,
            OutputPage.Encoding encoding, boolean openWhenDone) {
        super("Extract Metadata - " + container.getName());
        this.container = container;
        this.selection = selection;
        this.file = file;
        this.encoding = encoding;
        this.openWhenDone = openWhenDone;
        setUser(true);
    }

    @Override
    protected IStatus run(DBRProgressMonitor monitor) {
        DBCExecutionContext context = null;
        List<String> warnings = new ArrayList<>();
        try {
            context = FirebirdConnections.open(monitor, container);
            Connection connection = FirebirdConnections.jdbc(monitor, context);
            DatabaseInfo info = DatabaseInfo.read(connection);
            boolean utf8 = encoding == OutputPage.Encoding.UTF8;
            Charset charset = OutputPage.javaCharset(encoding, info.javaCharset());
            ExtractionFile.write(connection, selection, file, charset, utf8 ? "UTF8" : null,
                    progress(monitor, warnings));
        } catch (ExtractionCancelledException e) {
            return Status.CANCEL_STATUS;
        } catch (DBException | SQLException | IOException | RuntimeException e) {
            return new Status(IStatus.ERROR, "dev.fbtools.dbeaver.export", "Falha ao extrair o metadata: "
                    + e.getMessage(), e);
        } finally {
            if (context != null) {
                FirebirdConnections.close(context);
            }
        }
        UIUtils.asyncExec(() -> finished(warnings));
        return Status.OK_STATUS;
    }

    private void finished(List<String> warnings) {
        if (!warnings.isEmpty()) {
            UIUtils.showMessageBox(UIUtils.getActiveShell(), TITLE, "Script gravado em " + file + ".\n\n"
                    + String.join("\n", warnings), SWT.ICON_WARNING);
        }
        if (openWhenDone && Files.isRegularFile(file)) {
            EditorUtils.openExternalFileEditor(file, PlatformUI.getWorkbench().getActiveWorkbenchWindow());
        }
    }

    private ExtractionProgress progress(DBRProgressMonitor monitor, List<String> warnings) {
        return new ExtractionProgress() {
            @Override
            public void begin(int totalObjects) {
                monitor.beginTask("Extraindo metadata de " + container.getName(), Math.max(totalObjects, 1));
            }

            @Override
            public void extracted(String description) {
                monitor.subTask(description);
                monitor.worked(1);
            }

            @Override
            public void warn(String message) {
                warnings.add(message);
            }

            @Override
            public boolean isCancelled() {
                return monitor.isCanceled();
            }
        };
    }
}
