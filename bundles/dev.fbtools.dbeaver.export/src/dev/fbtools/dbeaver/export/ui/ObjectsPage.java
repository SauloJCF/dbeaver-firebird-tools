package dev.fbtools.dbeaver.export.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import dev.fbtools.dbeaver.export.extract.Selection;

/** Aba Objects: árvore dos objetos disponíveis à esquerda, lista dos selecionados à direita. */
final class ObjectsPage extends WizardPage {

    private static final String DOMAINS = "Domains";
    private static final String GENERATORS = "Generators";

    private final List<String> availableDomains;
    private final List<String> availableGenerators;
    private final Set<String> domains = new LinkedHashSet<>();
    private final Set<String> generators = new LinkedHashSet<>();

    private Tree available;
    private org.eclipse.swt.widgets.List selected;

    ObjectsPage(List<String> availableDomains, List<String> availableGenerators) {
        super("objects");
        setTitle("Objects");
        setDescription("Escolha os objetos a extrair.");
        this.availableDomains = availableDomains;
        this.availableGenerators = availableGenerators;
    }

    @Override
    public void createControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(3, false));

        available = new Tree(root, SWT.MULTI | SWT.BORDER);
        available.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        fillTree(DOMAINS, availableDomains);
        fillTree(GENERATORS, availableGenerators);

        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new GridLayout());
        button(buttons, "Add >", this::addSelected);
        button(buttons, "< Remove", this::removeSelected);
        button(buttons, "Extract all", this::addAll);
        button(buttons, "Clear", this::clear);

        selected = new org.eclipse.swt.widgets.List(root, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        selected.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label hint = new Label(root, SWT.NONE);
        hint.setText("Selecione itens na árvore e use Add, ou Extract all para tudo.");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        setControl(root);
        refresh();
    }

    /** Os objetos escolhidos; a página só fica completa com pelo menos um. */
    Selection selection() {
        return new Selection(Set.copyOf(domains), Set.copyOf(generators));
    }

    private void fillTree(String group, List<String> names) {
        TreeItem root = new TreeItem(available, SWT.NONE);
        root.setText(group + " (" + names.size() + ")");
        root.setData(group);
        for (String name : names) {
            TreeItem item = new TreeItem(root, SWT.NONE);
            item.setText(name);
            item.setData(group);
        }
        root.setExpanded(true);
    }

    private void addSelected() {
        for (TreeItem item : available.getSelection()) {
            if (item.getParentItem() == null) {
                // Selecionar o grupo adiciona todos os objetos dele.
                for (TreeItem child : item.getItems()) {
                    add(child);
                }
            } else {
                add(item);
            }
        }
        refresh();
    }

    private void add(TreeItem item) {
        (DOMAINS.equals(item.getData()) ? domains : generators).add(item.getText());
    }

    private void removeSelected() {
        for (String entry : selected.getSelection()) {
            String name = entry.substring(entry.indexOf(' ') + 1);
            (entry.startsWith("Domain ") ? domains : generators).remove(name);
        }
        refresh();
    }

    private void addAll() {
        domains.addAll(availableDomains);
        generators.addAll(availableGenerators);
        refresh();
    }

    private void clear() {
        domains.clear();
        generators.clear();
        refresh();
    }

    private void refresh() {
        selected.removeAll();
        domains.forEach(name -> selected.add("Domain " + name));
        generators.forEach(name -> selected.add("Generator " + name));
        setPageComplete(!domains.isEmpty() || !generators.isEmpty());
    }

    private static Button button(Composite parent, String text, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        button.addListener(SWT.Selection, event -> action.run());
        return button;
    }
}
