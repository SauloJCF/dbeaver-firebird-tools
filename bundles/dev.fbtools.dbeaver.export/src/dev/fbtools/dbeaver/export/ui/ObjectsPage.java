package dev.fbtools.dbeaver.export.ui;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    static final String DOMAINS = "Domains";
    static final String GENERATORS = "Generators";
    static final String EXCEPTIONS = "Exceptions";
    static final String ROLES = "Roles";
    static final String COLLATIONS = "Collations";
    static final String FILTERS = "BLOB filters";
    static final String UDFS = "UDFs";
    static final String TABLES = "Tables";
    static final String VIEWS = "Views";
    static final String INDEXES = "Indexes";
    static final String PROCEDURES = "Procedures";
    static final String FUNCTIONS = "Functions";
    static final String PACKAGES = "Packages";
    static final String TRIGGERS = "Triggers";
    static final String PUBLICATIONS = "Publications";
    static final String MAPPINGS = "Mappings";

    /** Grupo da árvore -> prefixo do objeto na lista dos selecionados, na ordem do tipo (não a das Fases). */
    private static final Map<String, String> LABELS = Map.ofEntries(Map.entry(DOMAINS, "Domain "),
            Map.entry(GENERATORS, "Generator "), Map.entry(EXCEPTIONS, "Exception "), Map.entry(ROLES, "Role "),
            Map.entry(COLLATIONS, "Collation "), Map.entry(FILTERS, "BLOB filter "), Map.entry(UDFS, "UDF "),
            Map.entry(TABLES, "Table "), Map.entry(VIEWS, "View "), Map.entry(INDEXES, "Index "),
            Map.entry(PROCEDURES, "Procedure "), Map.entry(FUNCTIONS, "Function "),
            Map.entry(PACKAGES, "Package "), Map.entry(TRIGGERS, "Trigger "),
            Map.entry(PUBLICATIONS, "Publication "), Map.entry(MAPPINGS, "Mapping "));
    private static final List<String> GROUPS = List.of(DOMAINS, GENERATORS, EXCEPTIONS, ROLES, COLLATIONS, FILTERS,
            UDFS, TABLES, VIEWS, INDEXES, PROCEDURES, FUNCTIONS, PACKAGES, TRIGGERS, PUBLICATIONS, MAPPINGS);

    private final Map<String, List<String>> available = new LinkedHashMap<>();
    private final Map<String, Set<String>> chosen = new LinkedHashMap<>();

    private Tree tree;
    private org.eclipse.swt.widgets.List selected;

    ObjectsPage(Map<String, List<String>> catalog) {
        super("objects");
        setTitle("Objects");
        setDescription("Escolha os objetos a extrair.");
        GROUPS.forEach(group -> {
            available.put(group, catalog.getOrDefault(group, List.of()));
            chosen.put(group, new LinkedHashSet<>());
        });
    }

    @Override
    public void createControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(3, false));

        tree = new Tree(root, SWT.MULTI | SWT.BORDER);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GROUPS.forEach(group -> fillTree(group, available.get(group)));

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
        return new Selection(chosen(DOMAINS), chosen(GENERATORS), chosen(TABLES), chosen(INDEXES), chosen(VIEWS),
                chosen(PROCEDURES), chosen(FUNCTIONS), chosen(PACKAGES), chosen(EXCEPTIONS), chosen(UDFS),
                chosen(COLLATIONS), chosen(FILTERS), chosen(ROLES), chosen(TRIGGERS), chosen(PUBLICATIONS),
                chosen(MAPPINGS));
    }

    private Set<String> chosen(String group) {
        return Set.copyOf(chosen.get(group));
    }

    private void fillTree(String group, List<String> names) {
        TreeItem root = new TreeItem(tree, SWT.NONE);
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
        for (TreeItem item : tree.getSelection()) {
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
        chosen.get((String) item.getData()).add(item.getText());
    }

    private void removeSelected() {
        for (String entry : selected.getSelection()) {
            for (String group : GROUPS) {
                String label = LABELS.get(group);
                if (entry.startsWith(label)) {
                    chosen.get(group).remove(entry.substring(label.length()));
                }
            }
        }
        refresh();
    }

    private void addAll() {
        GROUPS.forEach(group -> chosen.get(group).addAll(available.get(group)));
        refresh();
    }

    private void clear() {
        chosen.values().forEach(Set::clear);
        refresh();
    }

    private void refresh() {
        selected.removeAll();
        boolean any = false;
        for (String group : GROUPS) {
            for (String name : chosen.get(group)) {
                selected.add(LABELS.get(group) + name);
                any = true;
            }
        }
        setPageComplete(any);
    }

    private static Button button(Composite parent, String text, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        button.addListener(SWT.Selection, event -> action.run());
        return button;
    }
}
