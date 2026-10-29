package dev.fbtools.dbeaver.psqldebug.launch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import dev.fbtools.dbeaver.psqldebug.interp.DebugSession;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition.Trigger.Event;

/**
 * Diálogo do Launch: valor de cada parâmetro de entrada (texto convertido pelo servidor, {@code NULL} ou o valor
 * padrão), isolamento da transação ({@code READ COMMITTED} por padrão) e "commit ao terminar" (desmarcado). Numa
 * Trigger isolada, as entradas são {@code NEW.*} e {@code OLD.*}, uma linha por coluna da tabela, e os eventos
 * {@code INSERTING}/{@code UPDATING}/{@code DELETING}, em checkboxes. Os valores informados ficam lembrados para a
 * próxima vez, por Rotina.
 */
final class RoutineLaunchDialog extends TitleAreaDialog {

    /** O que o diálogo decidiu. */
    record Settings(List<Object> inputs, DebugSession.Isolation isolation, boolean commitOnFinish) {
    }

    private static final String VALUE = "Valor";
    private static final String NULL = "NULL";
    private static final String DEFAULT = "Padrão";

    /** Entrada da trigger de cada evento ({@code RoutineParser.parseTrigger}). */
    private static final Map<Event, String> EVENT_INPUTS =
            Map.of(Event.INSERT, "INSERTING", Event.UPDATE, "UPDATING", Event.DELETE, "DELETING");

    /** Altura máxima da tabela de {@code NEW}/{@code OLD}; com mais colunas, ela rola. */
    private static final int RECORD_HEIGHT = 360;

    private final RoutineDefinition routine;
    private final IDialogSettings history;
    /** Por entrada da Rotina: o modo e o valor, ou o checkbox de um evento de trigger. */
    private final Combo[] modes;
    private final Text[] values;
    private final Button[] events;
    private Combo isolation;
    private Button commit;
    private Settings settings;

    /** @param history onde ficam os valores lembrados desta Rotina */
    RoutineLaunchDialog(Shell shell, RoutineDefinition routine, IDialogSettings history) {
        super(shell);
        this.routine = routine;
        this.history = history;
        int inputs = routine.inputs().size();
        this.modes = new Combo[inputs];
        this.values = new Text[inputs];
        this.events = new Button[inputs];
    }

    /** @return o que foi escolhido, ou {@code null} se o diálogo foi cancelado */
    Settings settings() {
        return settings;
    }

    private boolean isTrigger() {
        return routine.kind() == RoutineDefinition.Kind.TRIGGER;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Firebird PSQL Debugger");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite body = new Composite(area, SWT.NONE);
        body.setLayout(new GridLayout(1, false));
        body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        if (isTrigger()) {
            RoutineDefinition.Trigger trigger = routine.trigger();
            setTitle("Depurar " + routine.displayName() + " (" + (trigger.before() ? "BEFORE " : "AFTER ")
                    + trigger.events().stream().map(Event::name).collect(Collectors.joining(" OR ")) + " em "
                    + trigger.table() + ")");
            setMessage("Trigger isolada: informe NEW e OLD e marque o evento. Cada valor é convertido pelo servidor "
                    + "para o tipo da coluna, como num CAST.");
            triggerInputs(body, trigger);
        } else {
            setTitle("Depurar " + routine.displayName());
            setMessage("Cada valor é convertido pelo servidor para o tipo do parâmetro, como num CAST.");
            parameters(body);
        }

        Group transaction = new Group(body, SWT.NONE);
        transaction.setText("Transação");
        transaction.setLayout(new GridLayout(2, false));
        transaction.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        new Label(transaction, SWT.NONE).setText("Isolamento:");
        isolation = new Combo(transaction, SWT.READ_ONLY);
        isolation.setItems("READ COMMITTED", "SNAPSHOT");
        isolation.select(0);
        commit = new Button(transaction, SWT.CHECK);
        commit.setText("Commit ao terminar");
        commit.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        Label hint = new Label(transaction, SWT.WRAP);
        hint.setText("Sem o commit, tudo o que a Rotina fizer é desfeito ao fim da sessão (rollback).");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        return area;
    }

    private void parameters(Composite body) {
        Group parameters = new Group(body, SWT.NONE);
        parameters.setText("Parâmetros de entrada");
        parameters.setLayout(new GridLayout(4, false));
        parameters.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        if (routine.inputs().isEmpty()) {
            new Label(parameters, SWT.NONE).setText("A Rotina não tem parâmetros de entrada.");
        }
        for (int i = 0; i < routine.inputs().size(); i++) {
            ParameterDeclaration input = routine.inputs().get(i);
            new Label(parameters, SWT.NONE).setText(input.name());
            Label type = new Label(parameters, SWT.NONE);
            type.setText(input.type().strip() + (input.notNull() ? " NOT NULL" : ""));
            valueCells(parameters, i, 260, true, true);
        }
    }

    /**
     * {@code NEW} e {@code OLD} lado a lado, uma linha por coluna da tabela, e os eventos, desmarcados. Sem valor, a
     * coluna vale {@code NULL}. O registro que a trigger não tem fica desabilitado, com {@code NULL}: {@code NEW} numa
     * trigger só de {@code DELETE}, {@code OLD} numa só de {@code INSERT} (o motor nem compila a referência). O
     * evento que não dispara a trigger também, sempre {@code FALSE}.
     */
    private void triggerInputs(Composite body, RoutineDefinition.Trigger trigger) {
        List<ParameterDeclaration> inputs = routine.inputs();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            index.put(inputs.get(i).name(), i);
        }
        boolean hasNew = trigger.events().contains(Event.INSERT) || trigger.events().contains(Event.UPDATE);
        boolean hasOld = trigger.events().contains(Event.UPDATE) || trigger.events().contains(Event.DELETE);

        Group record = new Group(body, SWT.NONE);
        record.setText("Colunas de " + trigger.table());
        record.setLayout(new GridLayout(1, false));
        record.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        ScrolledComposite scroll = new ScrolledComposite(record, SWT.V_SCROLL);
        Composite grid = new Composite(scroll, SWT.NONE);
        grid.setLayout(new GridLayout(6, false));
        for (String header : List.of("Coluna", "Tipo")) {
            new Label(grid, SWT.NONE).setText(header);
        }
        for (String header : List.of("NEW", "OLD")) {
            Label label = new Label(grid, SWT.NONE);
            label.setText(header);
            label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false, 2, 1));
        }
        for (int i = 0; i < inputs.size(); i++) {
            ParameterDeclaration input = inputs.get(i);
            if (!input.name().startsWith("NEW.")) {
                continue;
            }
            String column = input.name().substring("NEW.".length());
            new Label(grid, SWT.NONE).setText(column);
            new Label(grid, SWT.NONE).setText(input.type().strip());
            valueCells(grid, i, 160, false, hasNew);
            valueCells(grid, index.get("OLD." + column), 160, false, hasOld);
        }
        scroll.setContent(grid);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        Point size = grid.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        scroll.setMinSize(size);
        GridData scrollLayout = new GridData(SWT.FILL, SWT.FILL, true, true);
        scrollLayout.widthHint = size.x + scroll.getVerticalBar().getSize().x;
        scrollLayout.heightHint = Math.min(size.y, RECORD_HEIGHT);
        scroll.setLayoutData(scrollLayout);

        Group event = new Group(body, SWT.NONE);
        event.setText("Evento");
        event.setLayout(new GridLayout(3, false));
        event.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        for (Event fired : Event.values()) {
            String name = EVENT_INPUTS.get(fired);
            int i = index.get(name);
            Button check = new Button(event, SWT.CHECK);
            check.setText(name);
            check.setEnabled(trigger.events().contains(fired));
            check.setSelection(check.isEnabled() && history.getBoolean("event." + name));
            events[i] = check;
        }
    }

    /**
     * O modo ({@code Valor}, {@code NULL} e, se há, {@code Padrão}) e o texto do valor da entrada.
     *
     * @param offerDefault oferecer o valor padrão; fora dele, o modo inicial é {@code Valor}, senão {@code NULL}
     * @param enabled a entrada pode receber valor
     */
    private void valueCells(Composite parent, int i, int width, boolean offerDefault, boolean enabled) {
        ParameterDeclaration input = routine.inputs().get(i);
        Combo mode = new Combo(parent, SWT.READ_ONLY);
        mode.add(VALUE);
        mode.add(NULL);
        if (offerDefault && input.defaultValue() != null) {
            mode.add(DEFAULT);
        }
        Text value = new Text(parent, SWT.BORDER);
        GridData valueLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        valueLayout.widthHint = width;
        value.setLayoutData(valueLayout);

        String rememberedMode = history.get("mode." + input.name());
        String rememberedValue = history.get("value." + input.name());
        int selected = rememberedMode == null ? -1 : mode.indexOf(rememberedMode);
        if (selected < 0) {
            selected = !offerDefault ? mode.indexOf(NULL) : input.defaultValue() != null ? mode.indexOf(DEFAULT) : 0;
        }
        mode.select(enabled ? selected : mode.indexOf(NULL));
        mode.setEnabled(enabled);
        value.setText(rememberedValue == null ? "" : rememberedValue);
        value.setEnabled(enabled && VALUE.equals(mode.getText()));
        mode.addListener(SWT.Selection, event -> value.setEnabled(VALUE.equals(mode.getText())));
        modes[i] = mode;
        values[i] = value;
    }

    @Override
    protected void okPressed() {
        List<Object> chosen = isTrigger() ? triggerInputs() : parameterInputs();
        if (chosen == null) {
            return;
        }
        settings = new Settings(chosen,
                isolation.getSelectionIndex() == 1 ? DebugSession.Isolation.SNAPSHOT
                        : DebugSession.Isolation.READ_COMMITTED,
                commit.getSelection());
        super.okPressed();
    }

    /**
     * Como no {@code EXECUTE PROCEDURE}, só os últimos parâmetros podem ficar com o valor padrão: os que vêm antes de
     * um informado precisam de valor.
     *
     * @return os valores, ou {@code null} se há erro (mostrado no diálogo)
     */
    private List<Object> parameterInputs() {
        List<ParameterDeclaration> inputs = routine.inputs();
        int defaults = inputs.size();
        while (defaults > 0 && DEFAULT.equals(modes[defaults - 1].getText())) {
            defaults--;
        }
        for (int i = 0; i < defaults; i++) {
            if (DEFAULT.equals(modes[i].getText())) {
                setErrorMessage(inputs.get(i).name() + ": só os últimos parâmetros podem ficar com o valor padrão.");
                return null;
            }
        }
        List<Object> chosen = new ArrayList<>();
        for (int i = 0; i < defaults; i++) {
            chosen.add(NULL.equals(modes[i].getText()) ? null : values[i].getText());
        }
        for (int i = 0; i < inputs.size(); i++) {
            rememberValue(i);
        }
        return chosen;
    }

    /** Todas as entradas da trigger, na ordem dela: {@code NEW.*}, {@code OLD.*} e os eventos. */
    private List<Object> triggerInputs() {
        List<Object> chosen = new ArrayList<>();
        for (int i = 0; i < routine.inputs().size(); i++) {
            if (events[i] != null) {
                chosen.add(events[i].getSelection());
                if (events[i].isEnabled()) {
                    history.put("event." + routine.inputs().get(i).name(), events[i].getSelection());
                }
            } else {
                chosen.add(NULL.equals(modes[i].getText()) ? null : values[i].getText());
                if (modes[i].isEnabled()) {
                    rememberValue(i);
                }
            }
        }
        return chosen;
    }

    private void rememberValue(int i) {
        String name = routine.inputs().get(i).name();
        history.put("mode." + name, modes[i].getText());
        history.put("value." + name, values[i].getText());
    }
}
