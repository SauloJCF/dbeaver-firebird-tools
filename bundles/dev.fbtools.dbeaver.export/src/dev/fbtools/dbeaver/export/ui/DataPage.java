package dev.fbtools.dbeaver.export.ui;

import java.util.ArrayList;
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

/** Aba Data: as tabelas cujos dados vão para o Script de dados, as disponíveis à esquerda e as escolhidas à direita. */
final class DataPage extends WizardPage {

    private final List<String> available;
    private final Set<String> chosen = new LinkedHashSet<>();

    private org.eclipse.swt.widgets.List availableList;
    private org.eclipse.swt.widgets.List chosenList;

    DataPage(List<String> tables) {
        super("data");
        this.available = tables;
        setTitle("Data");
        setDescription("Escolha as tabelas cujos dados serão exportados (um script de dados à parte).");
    }

    @Override
    public void createControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(3, false));

        availableList = new org.eclipse.swt.widgets.List(root, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        availableList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        available.forEach(availableList::add);

        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new GridLayout());
        button(buttons, "Add >", () -> {
            chosen.addAll(List.of(availableList.getSelection()));
            refresh();
        });
        button(buttons, "< Remove", () -> {
            List.of(chosenList.getSelection()).forEach(chosen::remove);
            refresh();
        });
        button(buttons, "All", () -> {
            chosen.addAll(available);
            refresh();
        });
        button(buttons, "Clear", () -> {
            chosen.clear();
            refresh();
        });

        chosenList = new org.eclipse.swt.widgets.List(root, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        chosenList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label hint = new Label(root, SWT.WRAP);
        hint.setText("Os INSERTs saem na ordem da lista (alfabética); a ordem de FK automática vem depois. "
                + "Rode o script de dados depois do de metadata, com os triggers em mente: eles disparam nos INSERTs.");
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
        hintData.widthHint = 500;
        hint.setLayoutData(hintData);
        setControl(root);
        refresh();
    }

    /** As tabelas escolhidas, na ordem da lista de disponíveis. */
    List<String> tables() {
        List<String> ordered = new ArrayList<>();
        for (String table : available) {
            if (chosen.contains(table)) {
                ordered.add(table);
            }
        }
        return ordered;
    }

    private void refresh() {
        chosenList.removeAll();
        tables().forEach(chosenList::add);
        if (getContainer() != null) {
            getContainer().updateButtons();
        }
    }

    private static void button(Composite parent, String text, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        button.addListener(SWT.Selection, event -> action.run());
    }
}
