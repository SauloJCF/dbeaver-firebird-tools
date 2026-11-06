package dev.fbtools.dbeaver.export.ui;

import java.util.List;
import java.util.Map;

import org.eclipse.jface.wizard.Wizard;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

/** Wizard "Extract Metadata": abas Objects e Output. Ao concluir, agenda o job de background e fecha. */
final class ExtractMetadataWizard extends Wizard {

    private final DBPDataSourceContainer container;
    private final ObjectsPage objects;
    private final OutputPage output;

    ExtractMetadataWizard(DBPDataSourceContainer container, Map<String, List<String>> available) {
        this.container = container;
        this.objects = new ObjectsPage(available);
        this.output = new OutputPage(container.getName());
        setWindowTitle("Extract Metadata - " + container.getName());
        setNeedsProgressMonitor(false);
    }

    @Override
    public void addPages() {
        addPage(objects);
        addPage(output);
    }

    @Override
    public boolean performFinish() {
        output.rememberDirectory();
        new ExtractMetadataJob(container, objects.selection(), objects.options(), output.path(), output.encoding(),
                output.openWhenDone())
                .schedule();
        return true;
    }
}
