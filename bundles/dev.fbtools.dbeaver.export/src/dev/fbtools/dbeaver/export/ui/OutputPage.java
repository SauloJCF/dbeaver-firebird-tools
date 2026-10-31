package dev.fbtools.dbeaver.export.ui;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/** Aba Output: arquivo (nome padrão {@code <conexão>_<AAAAMMDD-HHMM>.sql}), codificação e abrir ao terminar (ADR 0009). */
final class OutputPage extends WizardPage {

    private static final String PREFERENCES_NODE = "dev.fbtools.dbeaver.export";
    private static final String LAST_DIRECTORY = "lastDirectory";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /** Como o arquivo é codificado: o charset padrão do banco (com {@code SET NAMES} dele) ou UTF-8. */
    enum Encoding {
        DATABASE("Charset padrão do banco"), UTF8("UTF-8");

        private final String label;

        Encoding(String label) {
            this.label = label;
        }
    }

    private final String connectionName;
    private Text file;
    private Combo encoding;
    private Button openWhenDone;

    OutputPage(String connectionName) {
        super("output");
        this.connectionName = connectionName;
        setTitle("Output");
        setDescription("Arquivo do script de metadata.");
    }

    @Override
    public void createControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(3, false));

        new Label(root, SWT.NONE).setText("Arquivo:");
        file = new Text(root, SWT.BORDER);
        file.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        file.setText(defaultFile().toString());
        file.addModifyListener(event -> validate());
        Button browse = new Button(root, SWT.PUSH);
        browse.setText("Procurar...");
        browse.addListener(SWT.Selection, event -> browse());

        new Label(root, SWT.NONE).setText("Codificação do arquivo:");
        encoding = new Combo(root, SWT.READ_ONLY);
        for (Encoding value : Encoding.values()) {
            encoding.add(value.label);
        }
        encoding.select(0);
        encoding.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        openWhenDone = new Button(root, SWT.CHECK);
        openWhenDone.setText("Abrir no editor ao terminar");
        openWhenDone.setSelection(true);
        openWhenDone.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label note = new Label(root, SWT.WRAP);
        note.setText("Com o charset do banco, o script começa com SET NAMES dele. Abra o arquivo no editor com a mesma "
                + "codificação, senão os acentos aparecem trocados (o isql não tem esse problema).");
        GridData noteData = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
        noteData.widthHint = 500;
        note.setLayoutData(noteData);
        setControl(root);
        validate();
    }

    Path path() {
        return Path.of(file.getText().strip());
    }

    Encoding encoding() {
        return Encoding.values()[encoding.getSelectionIndex()];
    }

    boolean openWhenDone() {
        return openWhenDone.getSelection();
    }

    /** Guarda a pasta do arquivo para a próxima vez. */
    void rememberDirectory() {
        Path parent = path().toAbsolutePath().getParent();
        if (parent != null) {
            Preferences node = InstanceScope.INSTANCE.getNode(PREFERENCES_NODE);
            node.put(LAST_DIRECTORY, parent.toString());
            try {
                node.flush();
            } catch (BackingStoreException e) {
                // só a conveniência de lembrar a pasta se perde
            }
        }
    }

    /** O charset Java de {@code encoding()} dado o charset padrão do banco; UTF-8 vira {@code SET NAMES UTF8}. */
    static Charset javaCharset(Encoding encoding, Charset database) {
        return encoding == Encoding.UTF8 ? StandardCharsets.UTF_8 : database;
    }

    private Path defaultFile() {
        String remembered = InstanceScope.INSTANCE.getNode(PREFERENCES_NODE).get(LAST_DIRECTORY,
                System.getProperty("user.home"));
        String safeName = connectionName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return Path.of(remembered, safeName + "_" + STAMP.format(LocalDateTime.now()) + ".sql");
    }

    private void browse() {
        FileDialog dialog = new FileDialog(getShell(), SWT.SAVE);
        dialog.setOverwrite(true);
        dialog.setFilterExtensions(new String[] {"*.sql", "*.*"});
        Path current = path();
        if (current.getParent() != null) {
            dialog.setFilterPath(current.getParent().toString());
        }
        dialog.setFileName(current.getFileName().toString());
        String chosen = dialog.open();
        if (chosen != null) {
            file.setText(chosen);
        }
    }

    private void validate() {
        String text = file.getText().strip();
        String error = null;
        try {
            if (text.isEmpty() || Path.of(text).getFileName() == null) {
                error = "Informe o arquivo.";
            }
        } catch (InvalidPathException e) {
            error = "Caminho inválido.";
        }
        setErrorMessage(error);
        setPageComplete(error == null);
    }
}
