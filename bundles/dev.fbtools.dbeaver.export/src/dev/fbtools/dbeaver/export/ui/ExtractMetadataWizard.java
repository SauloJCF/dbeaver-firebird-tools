package dev.fbtools.dbeaver.export.ui;

import java.util.List;
import java.util.Map;

import org.eclipse.jface.wizard.Wizard;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

/**
 * Wizard "Extract Metadata": abas Objects, Data, Options e Output. Ao concluir, agenda o job de background e fecha. Dá
 * para concluir com só objetos (script de metadata), só tabelas em Data (script de dados) ou os dois.
 */
final class ExtractMetadataWizard extends Wizard {

    private final DBPDataSourceContainer container;
    private final ObjectsPage objects;
    private final DataPage data;
    private final OptionsPage options = new OptionsPage();
    private final OutputPage output;

    ExtractMetadataWizard(DBPDataSourceContainer container, Map<String, List<String>> available) {
        this.container = container;
        this.objects = new ObjectsPage(available);
        this.data = new DataPage(available.getOrDefault(ObjectsPage.TABLES, List.of()));
        this.output = new OutputPage(container.getName());
        setWindowTitle("Extract Metadata - " + container.getName());
        setNeedsProgressMonitor(false);
    }

    @Override
    public void addPages() {
        addPage(objects);
        addPage(data);
        addPage(options);
        addPage(output);
    }

    @Override
    public boolean canFinish() {
        return super.canFinish() && (objects.hasSelection() || !data.tables().isEmpty());
    }

    @Override
    public boolean performFinish() {
        output.rememberDirectory();
        options.remember();
        new ExtractMetadataJob(container, objects.hasSelection() ? objects.selection() : null, options.options(),
                output.path(), output.encoding(), output.openWhenDone(), data.tables(),
                options.dataOptions(output.maxFileBytes()), output.dataPath())
                .schedule();
        return true;
    }
}
