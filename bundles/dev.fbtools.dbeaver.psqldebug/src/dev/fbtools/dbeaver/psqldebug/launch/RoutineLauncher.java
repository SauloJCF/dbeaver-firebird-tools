package dev.fbtools.dbeaver.psqldebug.launch;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.osgi.framework.FrameworkUtil;

import dev.fbtools.dbeaver.psqldebug.debug.PsqlDebugTarget;
import dev.fbtools.dbeaver.psqldebug.debug.PsqlSourceLocator;
import dev.fbtools.dbeaver.psqldebug.debug.SessionConsole;
import dev.fbtools.dbeaver.psqldebug.debug.SourceId;
import dev.fbtools.dbeaver.psqldebug.editor.EditorBreakpoints;
import dev.fbtools.dbeaver.psqldebug.editor.RoutineEditors;
import dev.fbtools.dbeaver.psqldebug.editor.SqlEditors;
import dev.fbtools.dbeaver.psqldebug.interp.DebugSession;
import dev.fbtools.dbeaver.psqldebug.psql.PreCheck;
import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.StatementLines;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;

/**
 * Launch de uma Rotina: abre a conexão dedicada (um contexto isolado do DBeaver, fora de qualquer editor SQL), carrega
 * a Fonte, passa pelo Pré-check, mostra o diálogo e inicia a Sessão de debug na perspectiva Debug. A Rotina sai de um
 * objeto do DBeaver (procedure executável ou selecionável, function, trigger de tabela), recusado com o editor dele
 * sujo; de um
 * {@code EXECUTE BLOCK} do editor SQL, cujo texto é o código; ou do console do corpo de uma package ("Abrir Package"), a
 * rotina sob o cursor.
 */
final class RoutineLauncher {

    private static final String TITLE = "Firebird PSQL Debugger";
    private static final String LAUNCH_TYPE_ID = "dev.fbtools.dbeaver.psqldebug.launchType";
    private static final ILog LOG = ILog.of(RoutineLauncher.class);

    private RoutineLauncher() {
    }

    /** De onde sai o Launch: o que muda entre uma Rotina do DBeaver, um {@code EXECUTE BLOCK} e uma rotina de package. */
    private interface Subject {

        DBPDataSourceContainer container();

        /** Nome nas mensagens. */
        String name();

        /** Chave dos valores lembrados do diálogo, dentro da conexão. */
        String historyKey();

        /** Fonte e Rotina, na conexão dedicada, fora da thread de UI. */
        Loaded load(Connection connection) throws SQLException;

        /** Texto de que são os breakpoints e as pausas da Rotina. */
        default SourceId source(RoutineDefinition routine) {
            return SourceId.of(container().getId(), routine);
        }

        /** Linha do texto em que a Rotina começa: num {@code EXECUTE BLOCK}, a do bloco no editor SQL. */
        default int firstLine() {
            return 1;
        }

        /** Elemento de fonte do texto da Rotina, que o debug.ui mostra no editor ({@link PsqlSourceLocator}). */
        Object element();

        /** Aviso no console antes do início, que não o impede. */
        default void warn(SessionConsole console, Loaded loaded) {
        }
    }

    /**
     * Fonte carregada.
     *
     * @param text a Fonte: a do banco ou, num {@code EXECUTE BLOCK}, a do editor SQL
     * @param routine a Rotina; {@code null} se o launch é recusado
     * @param lines linhas com statement do texto, as que recebem os breakpoints dele; {@code null} se recusado
     * @param refusal por que o launch é recusado (o Pré-check, por exemplo), ou {@code null}
     */
    private record Loaded(String text, RoutineDefinition routine, NavigableSet<Integer> lines, String refusal) {
    }

    /** Fonte já carregada na conexão dedicada. */
    private record Prepared(DBCExecutionContext context, Connection connection, Loaded loaded) {
    }

    /** Procedure (executável ou selecionável) ou function do DBeaver: a Fonte do banco. */
    private record DatabaseRoutine(GenericProcedure procedure) implements Subject {

        @Override
        public DBPDataSourceContainer container() {
            return procedure.getDataSource().getContainer();
        }

        @Override
        public String name() {
            return procedure.getName();
        }

        @Override
        public String historyKey() {
            return isFunction() ? "FUNCTION " + procedure.getName() : procedure.getName();
        }

        private boolean isFunction() {
            return procedure.getProcedureType() == DBSProcedureType.FUNCTION;
        }

        @Override
        public Loaded load(Connection connection) throws SQLException {
            String text = isFunction() ? RoutineSourceLoader.loadFunction(connection, procedure.getName())
                    : RoutineSourceLoader.loadProcedure(connection, procedure.getName());
            return checked(name(), text, () -> RoutineParser.parse(text));
        }

        @Override
        public Object element() {
            return procedure;
        }

        @Override
        public void warn(SessionConsole console, Loaded loaded) {
            warnIfEditorDiffers(console, procedure, loaded);
        }
    }

    /**
     * Trigger de tabela do DBeaver, como Trigger isolada: a Fonte do banco, com {@code NEW.*} e {@code OLD.*} tipados
     * pelas colunas da tabela e os eventos como entradas do diálogo.
     */
    private record TableTrigger(DBSObject trigger) implements Subject {

        @Override
        public DBPDataSourceContainer container() {
            return trigger.getDataSource().getContainer();
        }

        @Override
        public String name() {
            return trigger.getName();
        }

        @Override
        public String historyKey() {
            return "TRIGGER " + trigger.getName();
        }

        @Override
        public Loaded load(Connection connection) throws SQLException {
            RoutineSourceLoader.TriggerSource source = RoutineSourceLoader.loadTrigger(connection, trigger.getName());
            return checked(name(), source.text(), () -> RoutineParser.parseTrigger(source.text(), source.columns()));
        }

        @Override
        public Object element() {
            return trigger;
        }

        @Override
        public void warn(SessionConsole console, Loaded loaded) {
            warnIfEditorDiffers(console, trigger, loaded);
        }
    }

    /**
     * {@code EXECUTE BLOCK} do editor SQL: o texto do editor é o código, sem regra de editor sujo, e as posições são as
     * do documento.
     *
     * @param source o texto do editor ({@link SqlEditors#sourceOf})
     * @param block o bloco, posto na posição dele no documento
     */
    private record EditorBlock(DBPDataSourceContainer container, SourceId source, SqlEditors.Block block)
            implements Subject {

        @Override
        public String name() {
            return "EXECUTE BLOCK";
        }

        @Override
        public String historyKey() {
            return "EXECUTE BLOCK";
        }

        @Override
        public Loaded load(Connection connection) {
            return checked(name(), block.text(), () -> RoutineParser.parse(block.text()));
        }

        @Override
        public SourceId source(RoutineDefinition routine) {
            return source;
        }

        @Override
        public int firstLine() {
            return block.line();
        }

        @Override
        public Object element() {
            return source;
        }
    }

    /**
     * Rotina do corpo de uma package, do console aberto por "Abrir Package". O corpo gravado no banco precisa ser o
     * texto do console: as posições dos breakpoints e da pausa são as dele.
     *
     * @param packageName nome da package como está nos metadados
     * @param routineName nome normalizado da rotina
     * @param editorText texto do console
     */
    private record ConsoleRoutine(DBPDataSourceContainer container, String packageName, RoutineDefinition.Kind kind,
            String routineName, String editorText) implements Subject {

        @Override
        public String name() {
            return packageName + "." + routineName;
        }

        @Override
        public String historyKey() {
            return kind + " " + name();
        }

        @Override
        public Loaded load(Connection connection) throws SQLException {
            RoutineSourceLoader.PackageSource pkg = RoutineSourceLoader.loadPackage(connection, packageName);
            if (editorText == null || !lines(pkg.body()).equals(lines(editorText))) {
                return new Loaded(pkg.body(), null, null, "O corpo de " + packageName + " gravado no banco não é mais "
                        + "o do console. Abra a package de novo (PSQL Debug > Abrir Package).");
            }
            Loaded loaded = checked(name(), pkg.body(),
                    () -> RoutineParser.parsePackageRoutine(pkg.header(), pkg.body(), kind, routineName));
            if (loaded.routine() == null) {
                return loaded;
            }
            NavigableSet<Integer> lines = new TreeSet<>();
            RoutineParser.parsePackageBody(pkg.body())
                    .forEach(routine -> lines.addAll(StatementLines.of(routine.routine())));
            return new Loaded(loaded.text(), loaded.routine(), lines, null);
        }

        private static List<String> lines(String text) {
            return text.lines().toList();
        }

        @Override
        public Object element() {
            return new SourceId(container.getId(), SourceId.Kind.PACKAGE, packageName);
        }
    }

    /**
     * Procedure, selecionável ou não, function ou trigger de tabela do editor do objeto
     * ({@link DebuggableRoutines#find}).
     */
    static void launch(Shell shell, DBSObject routine) {
        if (routine instanceof GenericProcedure procedure && procedure.getPackage() != null) {
            MessageDialog.openInformation(shell, TITLE,
                    "Rotina de package se depura do console do corpo dela (PSQL Debug > Abrir Package).");
            return;
        }
        if (hasDirtyEditor(routine)) {
            MessageDialog.openError(shell, TITLE, "Salve (compile) ou descarte as alterações antes de depurar.");
            return;
        }
        launch(shell, routine instanceof GenericProcedure procedure ? new DatabaseRoutine(procedure)
                : new TableTrigger(routine));
    }

    /**
     * Do editor SQL: a rotina sob o cursor, no console de uma package; nos outros editores, o {@code EXECUTE BLOCK}
     * selecionado ou o do cursor.
     */
    static void launch(Shell shell, SQLEditor editor) {
        SourceId source = SqlEditors.sourceOf(editor);
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        if (source == null || container == null) {
            return;
        }
        if (!container.isConnected()) {
            MessageDialog.openInformation(shell, TITLE,
                    "Conecte o editor a " + container.getName() + " antes de depurar.");
            return;
        }
        Subject subject;
        if (source.kind() == SourceId.Kind.PACKAGE) {
            RoutineParser.PackageRoutine routine;
            try {
                routine = SqlEditors.packageRoutineAt(editor);
            } catch (PsqlSyntaxException e) {
                MessageDialog.openError(shell, TITLE,
                        "O Pré-check recusou " + source.name() + ":\n\n" + PreCheck.unrecognized(e));
                return;
            }
            if (routine == null) {
                MessageDialog.openInformation(shell, TITLE,
                        "Ponha o cursor dentro da rotina de " + source.name() + " que quer depurar.");
                return;
            }
            subject = new ConsoleRoutine(container, source.name(), routine.routine().kind(),
                    routine.routine().name(), SqlEditors.text(editor));
        } else {
            SqlEditors.Block block = SqlEditors.activeBlock(editor);
            if (block == null) {
                MessageDialog.openInformation(shell, TITLE,
                        "Selecione o EXECUTE BLOCK que quer depurar, ou ponha o cursor nele.");
                return;
            }
            subject = new EditorBlock(container, source, block);
        }
        launch(shell, subject);
    }

    private static void launch(Shell shell, Subject subject) {
        Prepared prepared;
        try {
            prepared = prepare(subject);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            MessageDialog.openError(shell, TITLE,
                    "Não foi possível carregar " + subject.name() + ": " + cause.getMessage());
            return;
        }
        boolean started = false;
        try {
            started = configureAndStart(shell, subject, prepared);
        } catch (CoreException | RuntimeException e) {
            LOG.error("Falha ao iniciar a Sessão de debug", e);
            MessageDialog.openError(shell, TITLE, "Falha ao iniciar a Sessão de debug: " + e.getMessage());
        } finally {
            if (!started) {
                closeQuietly(prepared.context());
            }
        }
    }

    /**
     * Pré-check da Rotina. Um texto que o parser não reconhece também é recusado, com a posição do erro.
     *
     * @param parsing o AST da Rotina, a partir do texto
     */
    private static Loaded checked(String name, String text, Supplier<RoutineDefinition> parsing) {
        List<PreCheck.Finding> findings;
        RoutineDefinition routine = null;
        try {
            routine = parsing.get();
            findings = PreCheck.scan(routine);
        } catch (PsqlSyntaxException e) {
            findings = List.of(PreCheck.unrecognized(e));
        }
        if (!findings.isEmpty()) {
            return new Loaded(text, null, null, "O Pré-check recusou " + name + ":\n\n"
                    + findings.stream().map(PreCheck.Finding::toString).collect(Collectors.joining("\n")));
        }
        return new Loaded(text, routine, StatementLines.of(routine), null);
    }

    private static void closeQuietly(DBCExecutionContext context) {
        try {
            context.close();
        } catch (DBException e) {
            LOG.warn("Falha ao fechar a conexão da Sessão de debug", e);
        }
    }

    /** @return se a sessão começou; a partir daí a conexão é dela */
    private static boolean configureAndStart(Shell shell, Subject subject, Prepared prepared) throws CoreException {
        Loaded loaded = prepared.loaded();
        if (loaded.refusal() != null) {
            MessageDialog.openError(shell, TITLE, loaded.refusal());
            return false;
        }
        RoutineLaunchDialog dialog = new RoutineLaunchDialog(shell, loaded.routine(), history(subject));
        if (dialog.open() != Window.OK) {
            return false;
        }
        start(subject, prepared, dialog.settings());
        return true;
    }

    /** Conexão dedicada e Fonte, fora da thread de UI. */
    private static Prepared prepare(Subject subject) throws InvocationTargetException {
        Prepared[] prepared = new Prepared[1];
        UIUtils.runInProgressDialog(monitor -> {
            monitor.beginTask("Abrindo a conexão da Sessão de debug", 2);
            DBCExecutionContext context = null;
            try {
                context = openContext(monitor, subject.container());
                Connection connection = connection(monitor, context);
                monitor.worked(1);
                monitor.subTask("Carregando a Fonte");
                prepared[0] = new Prepared(context, connection, subject.load(connection));
                monitor.done();
            } catch (DBException | SQLException | RuntimeException e) {
                if (context != null) {
                    closeQuietly(context);
                }
                throw new InvocationTargetException(e);
            }
        });
        return prepared[0];
    }

    /** Contexto isolado do DBeaver, fora de qualquer editor SQL. */
    private static DBCExecutionContext openContext(DBRProgressMonitor monitor, DBPDataSourceContainer container)
            throws DBException {
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            throw new DBException("a conexão " + container.getName() + " não está aberta");
        }
        return dataSource.getDefaultInstance().openIsolatedContext(monitor, "Firebird PSQL Debugger", null);
    }

    private static Connection connection(DBRProgressMonitor monitor, DBCExecutionContext context)
            throws SQLException {
        Connection connection = ((JDBCExecutionContext) context).getConnection(monitor);
        connection.setAutoCommit(true); // a sessão escolhe o isolamento antes da primeira transação
        return connection;
    }

    /**
     * Acerta os breakpoints pelas linhas com statement da Fonte ({@link EditorBreakpoints#prepareLaunch}) e inicia a
     * sessão. Os stack frames mostram a linha no editor da Rotina deles: o do launch ou, depois de um step into, o da
     * Rotina chamada, achada no DBeaver pelo nome, ou o console do corpo da package dela ({@link PsqlSourceLocator}). O
     * controle da sessão é também a política das chamadas: step into na chamada do statement em que a execução parou,
     * ou breakpoint ao alcance dela.
     */
    private static void start(Subject subject, Prepared prepared, RoutineLaunchDialog.Settings settings)
            throws CoreException {
        RoutineDefinition routine = prepared.loaded().routine();
        DBPDataSourceContainer container = subject.container();
        SourceId source = subject.source(routine);
        EditorBreakpoints.prepareLaunch(source, prepared.loaded().lines(), subject.firstLine());
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = manager.getLaunchConfigurationType(LAUNCH_TYPE_ID);
        ILaunchConfigurationWorkingCopy configuration =
                type.newInstance(null, manager.generateLaunchConfigurationName(routine.displayName()));
        Launch launch = new Launch(configuration, ILaunchManager.DEBUG_MODE, new PsqlSourceLocator(
                text -> text.equals(source) ? subject.element() : sourceElement(container, text)));
        manager.addLaunch(launch);
        SessionConsole console = new SessionConsole(
                settings.isolation() == DebugSession.Isolation.SNAPSHOT ? "SNAPSHOT" : "READ COMMITTED");
        subject.warn(console, prepared.loaded());
        PsqlDebugTarget target = new PsqlDebugTarget(launch, source, routine, settings.inputs(),
                settings.commitOnFinish(),
                control -> new DebugSession(prepared.connection(), settings.isolation(), settings.commitOnFinish(),
                        control, control),
                prepared.context()::close, console);
        launch.addDebugTarget(target);
        showDebugPerspective();
        target.start();
    }

    /**
     * Elemento de fonte do texto de uma Rotina chamada: a procedure ou function do DBeaver, achada pelo nome; o corpo de
     * uma package é o do console dela, procurado na hora de mostrar a linha.
     */
    private static Object sourceElement(DBPDataSourceContainer container, SourceId text) {
        return switch (text.kind()) {
            case PROCEDURE, FUNCTION -> RoutineEditors.routine(container, text);
            case PACKAGE, EXECUTE_BLOCK -> text;
            case TRIGGER -> null;
        };
    }

    /**
     * As linhas dos breakpoints e da pausa são as da Fonte do banco montada pelo {@code RoutineSourceLoader}, que
     * segue o cabeçalho que o DBeaver gera no editor (issue 09). Se, numa linha de statement, o texto do editor aberto
     * não é o da Fonte do banco, o console avisa: a linha destacada e os breakpoints podem não bater.
     */
    private static void warnIfEditorDiffers(SessionConsole console, DBSObject routine, Loaded loaded) {
        String editorText = RoutineEditors.openText(routine);
        if (editorText == null) {
            return;
        }
        String[] editor = editorText.split("\\R", -1);
        String[] source = loaded.text().split("\\R", -1);
        for (int line : loaded.lines()) {
            if (line > editor.length || line > source.length
                    || !editor[line - 1].strip().equals(source[line - 1].strip())) {
                console.warn("Em " + routine.getName() + ", a linha " + line + " do editor não é a da Fonte do banco "
                        + "que o debugger executa: a linha destacada e os breakpoints podem não bater.");
                return;
            }
        }
    }

    /** Editor do DBeaver aberto para a Rotina com alterações não salvas. */
    private static boolean hasDirtyEditor(DBSObject object) {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    if (reference.isDirty() && isEditorOf(reference, object)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isEditorOf(IEditorReference reference, DBSObject object) {
        try {
            IEditorInput input = reference.getEditorInput();
            return input instanceof IDatabaseEditorInput database
                    && Objects.equals(database.getDatabaseObject(), object);
        } catch (PartInitException e) {
            return false;
        }
    }

    /** Valores lembrados do diálogo, por conexão e Rotina. */
    private static IDialogSettings history(Subject subject) {
        IDialogSettings root = PlatformUI.getDialogSettingsProvider(FrameworkUtil.getBundle(RoutineLauncher.class))
                .getDialogSettings();
        String key = "launch:" + subject.container().getId() + ":" + subject.historyKey();
        IDialogSettings section = root.getSection(key);
        return section != null ? section : root.addNewSection(key);
    }

    /**
     * "Abrir Package": escolhe uma package da conexão e mostra o corpo dela, a Fonte do banco, num console só de
     * leitura ({@link SqlEditors#openPackage}). A lista e o corpo são lidos numa conexão dedicada, aberta só para isso.
     */
    static void openPackage(Shell shell, IWorkbenchWindow window, DBPDataSourceContainer container) {
        if (!container.isConnected()) {
            MessageDialog.openInformation(shell, TITLE,
                    "Conecte-se a " + container.getName() + " antes de abrir uma package.");
            return;
        }
        List<String> names;
        try {
            names = read(container, "Lendo as packages", RoutineSourceLoader::listPackages);
        } catch (InvocationTargetException e) {
            MessageDialog.openError(shell, TITLE, "Não foi possível ler as packages de " + container.getName() + ": "
                    + (e.getCause() == null ? e : e.getCause()).getMessage());
            return;
        }
        if (names.isEmpty()) {
            MessageDialog.openInformation(shell, TITLE, container.getName() + " não tem package com corpo.");
            return;
        }
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider());
        dialog.setTitle(TITLE);
        dialog.setMessage("O corpo da package (Fonte do banco) abre num console só de leitura. Ponha lá os breakpoints "
                + "e use \"Depurar Rotina\" com o cursor na rotina que quer depurar.");
        dialog.setElements(names.toArray());
        dialog.setMultipleSelection(false);
        if (dialog.open() != Window.OK || !(dialog.getFirstResult() instanceof String name)) {
            return;
        }
        String body;
        try {
            body = read(container, "Carregando o corpo de " + name,
                    connection -> RoutineSourceLoader.loadPackage(connection, name).body());
        } catch (InvocationTargetException e) {
            MessageDialog.openError(shell, TITLE, "Não foi possível carregar " + name + ": "
                    + (e.getCause() == null ? e : e.getCause()).getMessage());
            return;
        }
        SqlEditors.openPackage(window, container, name, body);
    }

    @FunctionalInterface
    private interface Read<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Uma leitura numa conexão dedicada aberta e fechada só para ela, fora da thread de UI. */
    private static <T> T read(DBPDataSourceContainer container, String task, Read<T> read)
            throws InvocationTargetException {
        List<T> result = new ArrayList<>(1);
        UIUtils.runInProgressDialog(monitor -> {
            monitor.beginTask(task, 1);
            DBCExecutionContext context = null;
            try {
                context = openContext(monitor, container);
                result.add(read.run(connection(monitor, context)));
            } catch (DBException | SQLException | RuntimeException e) {
                throw new InvocationTargetException(e);
            } finally {
                if (context != null) {
                    closeQuietly(context);
                }
                monitor.done();
            }
        });
        return result.get(0);
    }

    /**
     * Antes de a sessão começar, para o debug.ui não perguntar se deve trocar de perspectiva. A view Debug fica
     * visível mesmo se o usuário a fechou: na pausa, é ela que seleciona o frame do topo, e sem frame selecionado o
     * debug.ui não destaca a linha nem enche a view Variables. O debug.ui só a reabre na pausa para um tipo de launch
     * com perspectiva, e o nosso não tem. {@code VIEW_VISIBLE}, como o debug.ui, para não tirar o foco do editor.
     */
    private static void showDebugPerspective() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        if (window == null && workbench.getWorkbenchWindowCount() > 0) {
            window = workbench.getWorkbenchWindows()[0];
        }
        IWorkbenchPage page;
        try {
            page = workbench.showPerspective(IDebugUIConstants.ID_DEBUG_PERSPECTIVE, window);
        } catch (WorkbenchException e) {
            LOG.error("Falha ao abrir a perspectiva Debug", e);
            return;
        }
        try {
            page.showView(IDebugUIConstants.ID_DEBUG_VIEW, null, IWorkbenchPage.VIEW_VISIBLE);
        } catch (PartInitException e) {
            LOG.error("Falha ao abrir a view Debug", e);
        }
    }
}
