package dev.fbtools.dbeaver.export.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import dev.fbtools.dbeaver.export.extract.ExtractionOptions;

/**
 * Aba Options: as opções do Script de metadata em três grupos (Geral, Metadata, Grants). Os valores ficam guardados nas
 * preferências e voltam na vez seguinte; os padrões são os do {@link ExtractionOptions#DEFAULT}.
 */
final class OptionsPage extends WizardPage {

    private static final String PREFERENCES_NODE = "dev.fbtools.dbeaver.export";
    private static final String PREFIX = "option.";

    private Button createDatabase;
    private Button omitHeader;
    private Button setGenerators;
    private Button descriptions;
    private Button decodeDomains;
    private Button createOrAlter;
    private Button noSetTerm;
    private Button alwaysQuote;
    private Button alwaysCharacterSet;
    private Button computedBySeparate;
    private Button useSequence;
    private Button grants;
    private Button grantsOnlySelected;

    OptionsPage() {
        super("options");
        setTitle("Options");
        setDescription("Como o script de metadata é escrito.");
    }

    @Override
    public void createControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout());

        Composite general = group(root, "Geral");
        createDatabase = check(general, "createDatabase", "Incluir CREATE DATABASE (sem usuário nem senha)");
        omitHeader = check(general, "omitHeader",
                "Sem cabeçalho SET SQL DIALECT / SET NAMES (o DBeaver não os executa)");

        Composite metadata = group(root, "Metadata");
        setGenerators = check(metadata, "setGenerators", "Set generators (SET GENERATOR com o valor atual)");
        descriptions = check(metadata, "descriptions", "Incluir descrições (COMMENT ON)");
        decodeDomains = check(metadata, "decodeDomains",
                "Decode domains (colunas e parâmetros com o tipo do domínio; o CHECK do domínio não vai)");
        createOrAlter = check(metadata, "createOrAlter",
                "CREATE OR ALTER para views, procedures, functions e triggers");
        noSetTerm = check(metadata, "noSetTerm",
                "Não usar SET TERM (rotinas terminam em END;, nem o isql nem o DBeaver rodam)");
        alwaysQuote = check(metadata, "alwaysQuote", "Always quote identifiers");
        alwaysCharacterSet = check(metadata, "alwaysCharacterSet",
                "CHARACTER SET / COLLATE em todas as colunas de texto");
        computedBySeparate = check(metadata, "computedBySeparate",
                "COMPUTED BY separado do CREATE TABLE (ALTER TABLE ... ADD)");
        useSequence = check(metadata, "useSequence",
                "SEQUENCE no lugar de GENERATOR (com START WITH e INCREMENT quando preciso)");

        Composite privileges = group(root, "Grants");
        grants = check(privileges, "grants", "Extrair privilégios (GRANT)");
        grantsOnlySelected = check(privileges, "grantsOnlySelected", "Só os privilégios dos objetos selecionados");
        grants.addListener(SWT.Selection, event -> grantsOnlySelected.setEnabled(grants.getSelection()));
        grantsOnlySelected.setEnabled(grants.getSelection());
        setControl(root);
    }

    ExtractionOptions options() {
        return new ExtractionOptions(createDatabase.getSelection(), alwaysCharacterSet.getSelection(),
                computedBySeparate.getSelection(), alwaysQuote.getSelection(), createOrAlter.getSelection(),
                noSetTerm.getSelection(), omitHeader.getSelection(), useSequence.getSelection(),
                setGenerators.getSelection(), descriptions.getSelection(), decodeDomains.getSelection(),
                grants.getSelection(), grantsOnlySelected.getSelection());
    }

    /** Guarda as escolhas para a próxima vez. */
    void remember() {
        Preferences node = InstanceScope.INSTANCE.getNode(PREFERENCES_NODE);
        for (Button button : new Button[] {createDatabase, omitHeader, setGenerators, descriptions, decodeDomains,
                createOrAlter, noSetTerm, alwaysQuote, alwaysCharacterSet, computedBySeparate, useSequence, grants,
                grantsOnlySelected}) {
            node.putBoolean(PREFIX + button.getData(), button.getSelection());
        }
        try {
            node.flush();
        } catch (BackingStoreException e) {
            // só a conveniência de lembrar as opções se perde
        }
    }

    private static Composite group(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayout(new GridLayout());
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return group;
    }

    /** Uma opção; o valor inicial é o que ficou guardado, ou o padrão de {@link ExtractionOptions#DEFAULT}. */
    private static Button check(Composite parent, String key, String text) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(text);
        button.setData(key);
        button.setSelection(InstanceScope.INSTANCE.getNode(PREFERENCES_NODE).getBoolean(PREFIX + key,
                defaultOf(key)));
        return button;
    }

    private static boolean defaultOf(String key) {
        ExtractionOptions defaults = ExtractionOptions.DEFAULT;
        return switch (key) {
            case "createDatabase" -> defaults.createDatabase();
            case "omitHeader" -> defaults.omitHeader();
            case "setGenerators" -> defaults.setGenerators();
            case "descriptions" -> defaults.descriptions();
            case "decodeDomains" -> defaults.decodeDomains();
            case "createOrAlter" -> defaults.createOrAlter();
            case "noSetTerm" -> defaults.noSetTerm();
            case "alwaysQuote" -> defaults.alwaysQuote();
            case "alwaysCharacterSet" -> defaults.alwaysCharacterSet();
            case "computedBySeparate" -> defaults.computedBySeparate();
            case "useSequence" -> defaults.useSequence();
            case "grants" -> defaults.grants();
            default -> defaults.grantsOnlySelected();
        };
    }
}
