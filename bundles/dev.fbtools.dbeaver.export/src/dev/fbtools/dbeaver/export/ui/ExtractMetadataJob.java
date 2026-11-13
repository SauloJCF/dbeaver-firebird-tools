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

import dev.fbtools.dbeaver.export.extract.DataFile;
import dev.fbtools.dbeaver.export.extract.DataOptions;
import dev.fbtools.dbeaver.export.extract.DatabaseInfo;
import dev.fbtools.dbeaver.export.extract.ExtractionCancelledException;
import dev.fbtools.dbeaver.export.extract.ExtractionFile;
import dev.fbtools.dbeaver.export.extract.ExtractionOptions;
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
    private final ExtractionOptions options;
    private final Path file;
    private final OutputPage.Encoding encoding;
    private final boolean openWhenDone;
    private final List<String> dataTables;
    private final DataOptions dataOptions;
    private final Path dataFile;
    private final List<Path> written = new ArrayList<>();

    /**
     * @param selection os objetos do script de metadata, ou {@code null} para gerar só o de dados
     * @param dataTables as tabelas do script de dados, na ordem em que saem; vazio para não gerar o de dados
     */
    ExtractMetadataJob(DBPDataSourceContainer container, Selection selection, ExtractionOptions options, Path file,
            OutputPage.Encoding encoding, boolean openWhenDone, List<String> dataTables, DataOptions dataOptions,
            Path dataFile) {
        super("Extract Metadata - " + container.getName());
        this.container = container;
        this.selection = selection;
        this.options = options;
        this.file = file;
        this.encoding = encoding;
        this.openWhenDone = openWhenDone;
        this.dataTables = dataTables;
        this.dataOptions = dataOptions;
        this.dataFile = dataFile;
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
            ExtractionProgress progress = progress(monitor, warnings);
            if (selection != null) {
                ExtractionFile.write(connection, selection, options, file, charset, utf8 ? "UTF8" : null, progress);
                written.add(file);
            }
            if (!dataTables.isEmpty()) {
                written.addAll(DataFile.write(connection, dataTables, dataOptions, dataFile, charset,
                        utf8 ? "UTF8" : null, progress));
            }
        } catch (ExtractionCancelledException e) {
            removeWritten();
            return Status.CANCEL_STATUS;
        } catch (DBException | SQLException | IOException | RuntimeException e) {
            removeWritten();
            return new Status(IStatus.ERROR, "dev.fbtools.dbeaver.export", "Falha ao extrair o metadata: "
                    + e.getMessage() + (warnings.isEmpty() ? "" : "\n\nAvisos até o erro:\n"
                    + String.join("\n", warnings)), e);
        } finally {
            if (context != null) {
                FirebirdConnections.close(context);
            }
        }
        UIUtils.asyncExec(() -> finished(warnings));
        return Status.OK_STATUS;
    }

    /**
     * Cancelar ou falhar nos dados não deixa o script de metadata, já completo, sozinho no disco: quem cancela espera
     * que nada tenha sido gravado, e um script de metadata sem os dados pedidos engana.
     */
    private void removeWritten() {
        for (Path path : written) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // o arquivo fica; o erro original é o que importa ao usuário
            }
        }
        written.clear();
    }

    private void finished(List<String> warnings) {
        if (!warnings.isEmpty()) {
            UIUtils.showMessageBox(UIUtils.getActiveShell(), TITLE, "Gravado em:\n"
                    + String.join("\n", written.stream().map(Path::toString).toList()) + "\n\n"
                    + String.join("\n", warnings), SWT.ICON_WARNING);
        }
        if (openWhenDone && selection != null && Files.isRegularFile(file)) {
            EditorUtils.openExternalFileEditor(file, PlatformUI.getWorkbench().getActiveWorkbenchWindow());
        }
    }

    private ExtractionProgress progress(DBRProgressMonitor monitor, List<String> warnings) {
        return new ExtractionProgress() {
            private boolean begun;

            @Override
            public void begin(int totalObjects) {
                // O metadata e os dados contam cada um o seu total; a barra é a do primeiro, e o segundo só troca o texto.
                if (!begun) {
                    begun = true;
                    monitor.beginTask("Extraindo de " + container.getName(), Math.max(totalObjects, 1));
                }
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
