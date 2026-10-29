package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import dev.fbtools.dbeaver.psqldebug.psql.CallSites;
import dev.fbtools.dbeaver.psqldebug.psql.SubroutineScopes;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Assignment;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Block;
import dev.fbtools.dbeaver.psqldebug.psql.ast.CloseCursor;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Continue;
import dev.fbtools.dbeaver.psqldebug.psql.ast.CursorDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.DmlStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ExecuteProcedure;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ExecuteStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Exit;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Fetch;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ForExecuteStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ForSelect;
import dev.fbtools.dbeaver.psqldebug.psql.ast.FunctionCall;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Handler;
import dev.fbtools.dbeaver.psqldebug.psql.ast.If;
import dev.fbtools.dbeaver.psqldebug.psql.ast.InAutonomousTransaction;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Leave;
import dev.fbtools.dbeaver.psqldebug.psql.ast.OpenCursor;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ProcedureSource;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Raise;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Return;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Suspend;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedUse;
import dev.fbtools.dbeaver.psqldebug.psql.ast.VariableDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.While;

/**
 * Percorre o AST da Rotina e executa a lógica procedural no cliente (ADR 0001). O servidor recebe só Fatias DML e
 * Avaliações de expressão. Não controla a transação: isso é da {@link DebugSession}.
 * <p>
 * Uma chamada Step into elegível ({@link CallSites}) é interpretada, com frame próprio e a Fonte do banco da Rotina
 * chamada, ou executada no servidor; quem escolhe é a {@link CallPolicy}.
 */
public final class Interpreter {

    private final Connection connection;
    private final ExpressionEvaluator evaluator;
    private final DmlExecutor dml;
    private final ExceptionRaiser exceptions;
    private final RoutineCatalog catalog;
    private final CallTargets targets;
    private final CallPolicy policy;
    private final TemporaryPackages packages;
    private final StatementHook hook;
    /** Frames das Rotinas em execução; o primeiro é o de cima. */
    private final Deque<Frame> frames = new ArrayDeque<>();

    /**
     * Interpretador que executa no servidor toda chamada de Rotina. Os Packages temporários de debug que ele criar não
     * são removidos; para isso, ver o outro construtor.
     */
    public Interpreter(Connection connection) {
        this(connection, new DatabaseRoutineCatalog(connection), CallPolicy.SERVER, new TemporaryPackages(connection));
    }

    /**
     * @param catalog de onde vêm as Rotinas chamadas interpretadas e as rotinas de package que uma chamada sem
     *            package alcança
     * @param packages onde ficam as cópias das sub-rotinas chamadas no servidor; quem cria o Interpretador os remove
     */
    public Interpreter(Connection connection, RoutineCatalog catalog, CallPolicy policy, TemporaryPackages packages) {
        this(connection, catalog, policy, packages, StatementHook.NONE);
    }

    /** @param hook chamado antes de cada statement (ver {@link StatementHook}) */
    public Interpreter(Connection connection, RoutineCatalog catalog, CallPolicy policy, TemporaryPackages packages,
            StatementHook hook) {
        this.connection = connection;
        this.evaluator = new ExpressionEvaluator(connection);
        this.dml = new DmlExecutor(connection, evaluator);
        this.exceptions = new ExceptionRaiser(connection, evaluator);
        this.catalog = catalog;
        this.targets = new CallTargets(catalog);
        this.policy = policy;
        this.packages = packages;
        this.hook = hook;
    }

    /**
     * Pilha de frames, do de cima (a Rotina em execução) até a Rotina raiz. Uma procedure parada no {@code SUSPEND}
     * enquanto quem a chamou percorre o corpo do {@code FOR SELECT} sobre ela não aparece: ela volta à pilha quando
     * o laço busca a linha seguinte.
     */
    public List<Frame> frames() {
        return List.copyOf(frames);
    }

    /**
     * Executa a Rotina como executável: uma procedure como no {@code EXECUTE PROCEDURE} nativo, uma function como
     * no {@code SELECT f(...) FROM RDB$DATABASE}, um {@code EXECUTE BLOCK} sem {@code SUSPEND} como executado
     * direto. Se ela falhar, o que ela alterou é desfeito (savepoint). Um {@code SUSPEND} devolve os valores das
     * saídas e termina a Rotina, como no motor.
     *
     * @param inputs valores dos parâmetros de entrada, na ordem declarada; os que faltarem no fim usam o valor
     *            padrão do parâmetro
     * @return as saídas; numa function, uma só, com o nome da function e o valor do {@code RETURN}
     */
    public ProcedureResult execute(RoutineDefinition routine, List<?> inputs) throws SQLException {
        return call(Callee.root(routine), new SavepointStack(connection), values(inputs), null);
    }

    /**
     * Executa a procedure selecionável como um {@code SELECT * FROM sp(...)} que lê todas as linhas: cada
     * {@code SUSPEND} entrega a {@code rows}, na hora, os valores das saídas na ordem do {@code RETURNS}. O fim da
     * Rotina não emite linha. Se ela falhar, as linhas já entregues ficam e só o que ela alterou depois do último
     * {@code SUSPEND} é desfeito, como no motor, que desfaz apenas a busca em andamento.
     *
     * @param inputs como em {@link #execute}
     */
    public void select(RoutineDefinition routine, List<?> inputs, Consumer<List<Object>> rows)
            throws SQLException {
        call(Callee.root(routine), new SavepointStack(connection), values(inputs), Objects.requireNonNull(rows));
    }

    /**
     * Valor novo para uma variável, digitado por quem depura com a execução parada. O texto é convertido pelo servidor
     * para o tipo da variável, como um {@code CAST} de texto ({@code '17.10.2026'} numa {@code DATE}, {@code '1.5'}
     * arredondado num {@code INTEGER}); {@code NULL}, sem aspas, é o valor nulo. Como numa atribuição, {@code NULL}
     * numa variável {@code NOT NULL} é erro, e a variável que o {@code SELECT} de um cursor aberto usa não muda. Num
     * erro, a variável fica como estava.
     *
     * @param frame frame de que a variável é ({@link Frame#variables()})
     */
    public void assignText(Frame frame, Variable variable, String text) throws SQLException {
        Object value = text.strip().equalsIgnoreCase("NULL") ? null
                : evaluator.assignable(Collections.singletonList(text), List.of(variable.type())).get(0);
        store(variable, value, frame);
    }

    /**
     * Valor de um watch com a execução parada: a expressão avaliada no servidor com as variáveis do frame, como as da
     * Rotina (Modo Careful), e o tipo que o servidor dá a ela. A avaliação roda sob um savepoint desfeito no fim, e
     * o que a expressão chama (uma function ou procedure com DML) não altera o banco. As variáveis e o
     * {@code ROW_COUNT} do frame ficam como estavam.
     *
     * @param frame frame da pilha da execução parada ({@link #frames()})
     * @param expression ver {@code RoutineParser.parseWatch}
     */
    public WatchValue watch(Frame frame, Expression expression) throws SQLException {
        return Savepoints.undone(connection, () -> evaluator.typedValue(expression, frame));
    }

    /**
     * Valores das entradas de uma chamada, cada um já convertido para o tipo do parâmetro, como o motor faz ao
     * passá-los.
     */
    private interface Arguments {

        int count();

        Object value(int index, ParameterDeclaration parameter) throws SQLException;
    }

    /** Valores informados de fora (launch), convertidos pelo servidor. */
    private Arguments values(List<?> inputs) {
        return new Arguments() {

            @Override
            public int count() {
                return inputs.size();
            }

            @Override
            public Object value(int index, ParameterDeclaration parameter) throws SQLException {
                return evaluator.convert(inputs.get(index), parameter.type());
            }
        };
    }

    /** Argumentos de uma chamada, avaliados no frame de quem chama. */
    private Arguments arguments(List<Expression> arguments, Frame caller) {
        return new Arguments() {

            @Override
            public int count() {
                return arguments.size();
            }

            @Override
            public Object value(int index, ParameterDeclaration parameter) throws SQLException {
                return evaluator.evaluate(arguments.get(index), caller, parameter.type());
            }
        };
    }

    /**
     * @param savepoints pilha da chamada, que diz depois a quem chamou se a Rotina passou alterações para ele
     * @param rows destino das linhas do {@code SUSPEND}, ou {@code null} para rodar como executável
     */
    private ProcedureResult call(Callee callee, SavepointStack savepoints, Arguments arguments,
            Consumer<List<Object>> rows) throws SQLException {
        RoutineDefinition routine = callee.routine();
        Set<String> packageRoutines =
                routine.packageName() == null ? Set.of() : catalog.packageRoutines(routine.packageName());
        Frame frame = new Frame(routine, callee.steppedInto(), savepoints, rows, packageRoutines, callee.parent(),
                callee.parentVariables(), packages);
        savepoints.beginCall();
        frames.push(frame);
        ProcedureResult result;
        try {
            result = run(routine, arguments, frame);
        } catch (SQLException | RuntimeException e) {
            savepoints.undoCall(e);
            throw e;
        } finally {
            frames.remove(frame);
        }
        savepoints.endCall();
        return result;
    }

    /** @return as saídas, ou {@code null} se a Rotina roda como selecionável */
    private ProcedureResult run(RoutineDefinition routine, Arguments arguments, Frame frame) throws SQLException {
        refuse(routine.unsupported());
        if (arguments.count() > routine.inputs().size()) {
            throw new PsqlExecutionException(routine.displayName() + " recebe " + routine.inputs().size()
                    + " parâmetro(s) de entrada, mas foram informados " + arguments.count());
        }
        for (int i = 0; i < routine.inputs().size(); i++) {
            bindInput(frame, routine.inputs().get(i), arguments, i);
        }
        for (ParameterDeclaration output : routine.outputs()) {
            frame.declare(output.name(), output.type(), output.notNull(), Variable.Kind.OUTPUT);
        }
        for (VariableDeclaration local : routine.declarations()) {
            Variable variable = frame.declare(local.name(), local.type(), local.notNull(), Variable.Kind.LOCAL);
            if (local.initialValue() != null) {
                assign(variable, local.initialValue(), frame);
            }
        }
        for (CursorDeclaration cursor : routine.cursors()) {
            frame.declareCursor(new Cursor(cursor.name(), cursor.scroll(), cursor.select(), cursor.position()));
        }

        ProcedureResult result = null;
        try {
            execute(routine.body(), frame); // o único desvio que chega aqui é o EXIT (ou o RETURN)
            // Na executável, o fim devolve as saídas e valida o NOT NULL delas, fora do alcance de qualquer WHEN.
            // Na selecionável, o fim não emite linha nem valida nada.
            if (routine.returnValue() != null) {
                result = new ProcedureResult(List.of(routine.name()), Collections.singletonList(returned(frame)));
            } else if (routine.trigger() != null) {
                result = triggerResult(routine, frame);
            } else if (frame.rows() == null) {
                result = new ProcedureResult(routine.outputs().stream().map(ParameterDeclaration::name).toList(),
                        frame.outputValues());
            }
        } catch (SQLException | RuntimeException e) {
            try {
                frame.closeCursors();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        frame.closeCursors(); // o motor fecha os cursores que a Rotina deixou abertos
        return result;
    }

    /**
     * Saída de uma Trigger isolada: numa {@code BEFORE}, os valores finais de {@code NEW.*}, que é o que o motor
     * gravaria (com as atribuições da trigger); numa {@code AFTER}, nada.
     */
    private static ProcedureResult triggerResult(RoutineDefinition trigger, Frame frame) {
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (trigger.trigger().before()) {
            for (ParameterDeclaration input : trigger.inputs()) {
                if (input.name().startsWith("NEW.")) {
                    names.add(input.name());
                    values.add(frame.get(input.name()).value());
                }
            }
        }
        return new ProcedureResult(names, values);
    }

    private void bindInput(Frame frame, ParameterDeclaration input, Arguments arguments, int index)
            throws SQLException {
        Variable variable = frame.declare(input.name(), input.type(), input.notNull(), Variable.Kind.INPUT);
        if (index < arguments.count()) {
            store(variable, arguments.value(index, input), frame);
        } else if (input.defaultValue() != null) {
            assign(variable, input.defaultValue(), frame);
        } else {
            throw new PsqlExecutionException("Parâmetro de entrada sem valor e sem padrão: " + input.name());
        }
    }

    /** @return o desvio que interrompeu o statement, ou {@code null} se ele terminou normalmente */
    private Jump execute(Statement statement, Frame frame) throws SQLException {
        frame.position(statement.position());
        hook.beforeStatement(frame, statement);
        switch (statement) {
            case Block block -> {
                return executeBlock(block, frame);
            }
            case Assignment assignment -> {
                assign(assignment, frame);
                return null;
            }
            case If branch -> {
                if (evaluator.isTrue(branch.condition(), frame)) {
                    return execute(branch.thenBranch(), frame);
                }
                return branch.elseBranch() == null ? null : execute(branch.elseBranch(), frame);
            }
            case While loop -> {
                return executeLoop(loop, frame);
            }
            case ForSelect loop -> {
                return executeLoop(loop, frame);
            }
            case ForExecuteStatement loop -> {
                return executeLoop(loop, frame);
            }
            case Leave leave -> {
                return new Jump(Jump.Kind.LEAVE, leave.label());
            }
            case Continue next -> {
                return new Jump(Jump.Kind.CONTINUE, next.label());
            }
            case Exit exit -> {
                return Jump.EXIT;
            }
            case DmlStatement slice -> {
                refuse(slice.unsupported());
                assignInto(slice.into(), dml.execute(slice, frame), frame);
                return null;
            }
            case ExecuteStatement dynamic -> {
                refuse(dynamic.unsupported());
                assignInto(dynamic.into(), dml.execute(dynamic, frame), frame);
                return null;
            }
            case ExecuteProcedure call -> {
                executeProcedure(call, frame);
                return null;
            }
            case OpenCursor open -> {
                Cursor cursor = frame.cursor(open.cursor());
                refuse(cursor.select().unsupported());
                dml.open(cursor, cursor.select(), frame);
                return null;
            }
            case Fetch fetch -> {
                fetch(fetch, frame);
                return null;
            }
            case CloseCursor close -> {
                frame.cursor(close.cursor()).close();
                return null;
            }
            case Raise raise -> {
                raise(raise, frame);
                return null;
            }
            case Suspend suspend -> {
                return suspend(frame);
            }
            case Return value -> {
                return returnValue(value, frame);
            }
            case InAutonomousTransaction autonomous -> {
                refuse(List.of(new UnsupportedUse(UnsupportedConstruct.IN_AUTONOMOUS_TRANSACTION,
                        autonomous.position())));
                return null;
            }
        }
    }

    private Jump execute(List<Statement> statements, Frame frame) throws SQLException {
        for (Statement inner : statements) {
            Jump jump = execute(inner, frame);
            if (jump != null) {
                return jump;
            }
        }
        return null;
    }

    /**
     * Bloco: um nível de savepoint ({@link SavepointStack}). Um erro sai do bloco deixando o nível no lugar, a
     * menos que um {@code WHEN} do bloco o trate.
     */
    private Jump executeBlock(Block block, Frame frame) throws SQLException {
        SavepointStack.Level level = frame.savepoints().push(!block.handlers().isEmpty());
        Jump jump;
        try {
            jump = execute(block.statements(), frame);
        } catch (SQLException e) {
            if (block.handlers().isEmpty()) {
                throw e; // quem desfaz é o bloco que tratar o erro
            }
            return handle(block, level, e, frame);
        }
        frame.savepoints().release(level);
        return jump;
    }

    /**
     * Erro que chegou a um bloco com {@code WHEN}. Como no motor, primeiro é desfeito o que está acima do bloco (o
     * statement em andamento), não o que os statements já terminados do bloco fizeram. Depois o handler é escolhido:
     * o motor testa todos, na ordem, e o que casa limpa o erro. Por isso um {@code WHEN} específico depois de outro
     * que casou nunca casa, mas um {@code WHEN ANY} depois dele casa e é o que roda, já sem erro ({@code GDSCODE} 0,
     * {@code EXCEPTION;} sem efeito). Sem handler que case, o erro continua subindo. {@code GDSCODE},
     * {@code SQLCODE} e {@code SQLSTATE} valem o erro só até o fim do handler, mesmo dentro de outro handler.
     */
    private Jump handle(Block block, SavepointStack.Level level, SQLException error, Frame frame)
            throws SQLException {
        SavepointStack savepoints = frame.savepoints();
        savepoints.rollbackAbove(level);
        ErrorStatus status = ErrorStatus.of(error);
        Handler chosen = null;
        ErrorStatus seen = null;
        for (Handler handler : block.handlers()) {
            ErrorStatus current = status;
            if (handler.conditions().stream().anyMatch(current::matches)) {
                chosen = handler;
                seen = current;
                status = ErrorStatus.NONE;
            }
        }
        if (chosen == null) {
            throw error;
        }
        frame.handledError(seen);
        savepoints.enterHandler(level);
        Jump jump;
        try {
            jump = execute(chosen.action(), frame);
        } finally {
            savepoints.leaveHandler(level);
            frame.handledError(ErrorStatus.NONE);
        }
        savepoints.release(level);
        savepoints.nestedErrorHandled();
        return jump;
    }

    private Jump executeLoop(While loop, Frame frame) throws SQLException {
        while (evaluator.isTrue(loop.condition(), frame)) {
            Jump jump = execute(loop.body(), frame);
            frame.position(loop.position());
            if (jump != null) {
                if (!jump.targets(loop.label())) {
                    return jump; // EXIT, ou LEAVE/CONTINUE com o label de um laço de fora
                }
                if (jump.kind() == Jump.Kind.LEAVE) {
                    break;
                }
            }
            nextIteration(loop, frame);
        }
        return null;
    }

    /** Antes de cada volta de um laço depois da primeira (ver {@link StatementHook}). */
    private void nextIteration(Statement loop, Frame frame) {
        frame.position(loop.position());
        hook.beforeStatement(frame, loop);
    }

    /**
     * {@code FOR SELECT}: o cursor é aberto no servidor e lido linha a linha. Sair do laço, por fim, desvio ou erro,
     * fecha o cursor. Como no motor, o laço tem nível de savepoint próprio só se o nível atual já tinha alterações.
     * Com uma procedure como única fonte, ela é interpretada sempre que der (ver {@link CallPolicy}).
     */
    private Jump executeLoop(ForSelect loop, Frame frame) throws SQLException {
        DmlStatement select = loop.select();
        refuse(select.unsupported());
        ProcedureSource source = loop.procedure();
        CallSites.CallSite site = source == null ? null
                : new CallSites.CallSite(CallSites.Form.FOR_SELECT, source.packageName(), source.name(),
                        source.position());
        Callee callee = site == null ? null : callee(site, RoutineDefinition.Kind.PROCEDURE, frame);
        if (callee != null) {
            return executeLoop(loop, site, callee, frame);
        }
        SavepointStack.Level level = frame.savepoints().pushIfChanged();
        Jump result = null;
        try (Cursor cursor = new Cursor(loop.cursor(), false, null, loop.position())) {
            dml.open(cursor, select, frame);
            frame.clearRowCount();
            frame.enterLoop(cursor);
            try {
                for (List<Object> row = next(cursor); row != null; row = next(cursor)) {
                    frame.rowFetched();
                    if (!select.into().isEmpty()) {
                        assignInto(select.into(), dml.assignable(row, select.into(), frame), frame);
                    }
                    Jump jump = execute(loop.body(), frame);
                    if (jump != null && !jump.targets(loop.label())) {
                        result = jump;
                        break;
                    }
                    if (jump != null && jump.kind() == Jump.Kind.LEAVE) {
                        break;
                    }
                    nextIteration(loop, frame);
                }
            } finally {
                frame.leaveLoop(cursor);
            }
        }
        frame.savepoints().release(level);
        return result;
    }

    /**
     * {@code FOR SELECT} sobre procedure interpretada, que roda como gerador: a cada {@code SUSPEND} dela, o corpo
     * do laço roda com ela parada ali, fora da pilha de frames e sem savepoint aberto; a linha seguinte a faz
     * continuar. Sair do laço, por desvio ou erro de quem chama, abandona a procedure parada, como o motor faz ao
     * fechar o cursor. O que ela altera vai para o nível de quem chama, como num cursor do servidor.
     * <p>
     * Uma procedure que a camada de debug não acompanha pode passar a ser acompanhada numa volta seguinte, antes de
     * continuar depois do {@code SUSPEND}: com o step into na linha do laço, ou com um breakpoint posto nela depois do
     * começo do laço ({@link #follow}).
     */
    private Jump executeLoop(ForSelect loop, CallSites.CallSite site, Callee callee, Frame frame)
            throws SQLException {
        ProcedureSource source = loop.procedure();
        RoutineDefinition procedure = callee.routine();
        List<Integer> columns = columns(source, procedure);
        List<String> labels = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ParameterDeclaration output = procedure.outputs().get(columns.get(i));
            labels.add(source.columns().isEmpty() ? output.name() : source.columns().get(i).label());
            types.add(output.type());
        }
        SavepointStack.Level level = frame.savepoints().pushIfChanged();
        SavepointStack savepoints = new SavepointStack(connection);
        Object owner = new Object();
        Jump result = null;
        try (Cursor cursor = new Cursor(loop.cursor(), false, null, loop.position())) {
            cursor.openRows(labels, types);
            frame.clearRowCount();
            frame.enterLoop(cursor);
            try {
                frame.savepoints().beforeChange();
                call(callee, savepoints, arguments(source.arguments(), frame), row -> {
                    if (savepoints.takeChanges()) {
                        frame.savepoints().changed();
                    }
                    List<Frame> paused = pauseAbove(frame);
                    try {
                        List<Object> values = new ArrayList<>(columns.size());
                        columns.forEach(column -> values.add(row.get(column)));
                        Jump jump = iterate(loop, cursor, values, frame);
                        if (jump != null && !jump.targets(loop.label())) {
                            throw new LoopExit(owner, jump, null);
                        }
                        if (jump != null && jump.kind() == Jump.Kind.LEAVE) {
                            throw new LoopExit(owner, null, null);
                        }
                        nextIteration(loop, frame); // com a procedure ainda fora da pilha
                        follow(site, callee, frame, paused);
                        frame.savepoints().beforeChange(); // a procedure continua e pode alterar dados
                    } catch (SQLException e) {
                        throw new LoopExit(owner, null, e);
                    } finally {
                        resume(paused);
                    }
                });
                if (savepoints.takeChanges()) {
                    frame.savepoints().changed();
                }
            } catch (LoopExit exit) {
                if (exit.owner != owner) {
                    throw exit; // de um laço de fora, que passa por esta procedure parada
                }
                if (exit.error != null) {
                    throw exit.error;
                }
                result = exit.jump;
            } finally {
                frame.leaveLoop(cursor);
            }
        }
        frame.savepoints().release(level);
        return result;
    }

    /**
     * Numa volta seguinte do {@code FOR SELECT} sobre procedure interpretada, antes de ela continuar: se a camada de
     * debug ainda não a acompanha, a política pode passar a acompanhá-la (step into na linha do laço, ou um breakpoint
     * ao alcance dela).
     *
     * @param paused frames tirados da pilha durante o corpo do laço; o último é o da procedure
     */
    private void follow(CallSites.CallSite site, Callee callee, Frame caller, List<Frame> paused)
            throws SQLException {
        Frame procedure = paused.isEmpty() ? null : paused.get(paused.size() - 1);
        if (procedure == null || procedure.steppedInto()) {
            return;
        }
        RoutineDefinition text = callee.parent() != null ? callee.parent().routine() : callee.routine();
        if (policy.stepInto(site, caller) || policy.stopsInside(() -> targets.reach(callee.routine(), text))) {
            procedure.follow();
        }
    }

    /** Uma linha do {@code FOR SELECT} sobre procedure interpretada: alvos do {@code INTO} e corpo do laço. */
    private Jump iterate(ForSelect loop, Cursor cursor, List<Object> values, Frame frame) throws SQLException {
        frame.position(loop.position());
        frame.rowFetched();
        cursor.current(values);
        List<String> into = loop.select().into();
        if (!into.isEmpty()) {
            assignInto(into, dml.assignable(values, into, frame), frame);
        }
        return execute(loop.body(), frame);
    }

    /** Posição de cada coluna do {@code SELECT} entre as saídas da procedure. */
    private static List<Integer> columns(ProcedureSource source, RoutineDefinition callee) {
        List<String> outputs = callee.outputs().stream().map(ParameterDeclaration::name).toList();
        if (source.columns().isEmpty()) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < outputs.size(); i++) {
                all.add(i);
            }
            return all;
        }
        List<Integer> columns = new ArrayList<>();
        for (ProcedureSource.Column column : source.columns()) {
            int index = outputs.indexOf(column.name());
            if (index < 0) {
                throw new PsqlExecutionException(source.position() + ": " + callee.displayName()
                        + " não tem a saída " + column.name());
            }
            columns.add(index);
        }
        return columns;
    }

    /** Tira da pilha os frames acima de {@code frame}: Rotinas paradas no {@code SUSPEND}. */
    private List<Frame> pauseAbove(Frame frame) {
        List<Frame> paused = new ArrayList<>();
        while (frames.peek() != frame) {
            paused.add(frames.pop());
        }
        return paused;
    }

    private void resume(List<Frame> paused) {
        for (int i = paused.size() - 1; i >= 0; i--) {
            frames.push(paused.get(i));
        }
    }

    /**
     * Saída de um {@code FOR SELECT} sobre procedure interpretada, de dentro do corpo do laço: atravessa a
     * procedure parada no {@code SUSPEND} sem que um {@code WHEN} dela a trate (os handlers só tratam
     * {@link SQLException}).
     */
    private static final class LoopExit extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** O laço que trata a saída. */
        final transient Object owner;
        /** Desvio para fora do laço, ou {@code null} se o laço só termina. */
        final transient Jump jump;
        /** Erro do corpo do laço, que continua subindo de quem chama; ou {@code null}. */
        final SQLException error;

        LoopExit(Object owner, Jump jump, SQLException error) {
            super(null, null, false, false);
            this.owner = owner;
            this.jump = jump;
            this.error = error;
        }
    }

    /**
     * {@code EXECUTE PROCEDURE}. O que a procedure altera vai para o nível de quem chama; no servidor não há como
     * saber se ela alterou algo, e conta como alteração.
     */
    private void executeProcedure(ExecuteProcedure call, Frame frame) throws SQLException {
        Callee callee = callee(new CallSites.CallSite(CallSites.Form.EXECUTE_PROCEDURE, call.packageName(),
                call.name(), call.position()), RoutineDefinition.Kind.PROCEDURE, frame);
        frame.savepoints().beforeChange();
        List<Object> outputs;
        if (callee == null) {
            outputs = dml.executeProcedure(frame.serverName(call.packageName(), call.name()), call.arguments(),
                    frame);
            frame.savepoints().changed();
        } else {
            outputs = callInterpreted(callee, call.arguments(), frame).values();
        }
        if (!call.returningValues().isEmpty()) {
            assignInto(call.returningValues(), dml.assignable(outputs, call.returningValues(), frame), frame);
        }
    }

    /**
     * Atribuição. {@code x = F(...)} interpretada: o valor que a function devolve é convertido para o tipo de
     * {@code x}, como numa atribuição.
     */
    private void assign(Assignment assignment, Frame frame) throws SQLException {
        Variable target = frame.get(assignment.target());
        FunctionCall call = assignment.call();
        Callee callee = call == null ? null
                : callee(new CallSites.CallSite(CallSites.Form.ASSIGNMENT, call.packageName(), call.name(),
                        call.position()), RoutineDefinition.Kind.FUNCTION, frame);
        if (callee == null) {
            assign(target, assignment.value(), frame);
            return;
        }
        frame.savepoints().beforeChange();
        Object value = callInterpreted(callee, call.arguments(), frame).values().get(0);
        store(target, evaluator.assignable(Collections.singletonList(value), List.of(target.type())).get(0), frame);
    }

    /** Executável ou function interpretada. O que ela alterou passa para o nível de quem chama. */
    private ProcedureResult callInterpreted(Callee callee, List<Expression> arguments, Frame caller)
            throws SQLException {
        SavepointStack savepoints = new SavepointStack(connection);
        ProcedureResult result = call(callee, savepoints, arguments(arguments, caller), null);
        if (savepoints.takeChanges()) {
            caller.savepoints().changed();
        }
        return result;
    }

    /**
     * Rotina a interpretar.
     *
     * @param steppedInto a camada de debug acompanha a Rotina (ver {@link Frame#steppedInto()})
     * @param parent numa sub-rotina, o frame da Rotina que a declara (ver {@link Frame#parent()}); senão {@code null}
     * @param parentVariables variáveis de {@code parent} que a sub-rotina enxerga
     */
    private record Callee(RoutineDefinition routine, boolean steppedInto, Frame parent, Set<String> parentVariables) {

        static Callee root(RoutineDefinition routine) {
            return new Callee(routine, true, null, Set.of());
        }
    }

    /**
     * Rotina chamada a interpretar, ou {@code null} para executar a chamada no servidor: quando a política pede o
     * servidor (exceto no {@code FOR SELECT} e numa rotina privada da package de quem chama, ver
     * {@link CallPolicy}), quando o nome não é de Rotina PSQL com Fonte do banco, ou quando a Fonte dela tem
     * construção que o Interpretador não executa (nesse caso a Rotina roda inteira no servidor, que é o gabarito).
     * Um nome sem package é primeiro o de uma sub-rotina ({@link #subroutineCallee}). Fora do step into, a Rotina é
     * acompanhada pela camada de debug se ela pode parar dentro da chamada ({@link CallPolicy#stopsInside}).
     *
     * @param kind {@link RoutineDefinition.Kind#PROCEDURE} ou {@link RoutineDefinition.Kind#FUNCTION}
     */
    private Callee callee(CallSites.CallSite site, RoutineDefinition.Kind kind, Frame caller) throws SQLException {
        boolean steppedInto = policy.stepInto(site, caller);
        Subroutine subroutine = site.packageName() == null ? caller.subroutine(kind, site.name()) : null;
        if (subroutine != null) {
            RoutineDefinition text = caller.subroutineOwner().routine();
            steppedInto = steppedInto || policy.stopsInside(() -> targets.reach(subroutine.routine(), text));
            return subroutineCallee(subroutine, site.form(), steppedInto, caller);
        }
        String callerPackage = caller.routine().packageName();
        if (!steppedInto && site.form() != CallSites.Form.FOR_SELECT
                && !isPrivate(site.packageName(), site.name(), caller)) {
            // No step over, a chamada vai ao servidor, a menos que a execução possa parar dentro dela.
            if (!policy.stopsInside(() -> reach(targets.interpretable(kind, callerPackage, site.packageName(),
                    site.name())))) {
                return null;
            }
            steppedInto = true;
        }
        RoutineDefinition routine = targets.interpretable(kind, callerPackage, site.packageName(), site.name());
        if (routine == null) {
            return null;
        }
        steppedInto = steppedInto || policy.stopsInside(() -> targets.reach(routine, routine));
        return new Callee(routine, steppedInto, null, Set.of());
    }

    /** Linhas que a chamada pode executar; vazio se a Rotina não é interpretada ({@code null}). */
    private Map<RoutineDefinition, NavigableSet<Integer>> reach(RoutineDefinition routine) throws SQLException {
        return routine == null ? Map.of() : targets.reach(routine, routine);
    }

    /**
     * Sub-rotina chamada. No step over, ela vai ao servidor pela cópia no Package temporário de debug, exceto no
     * {@code FOR SELECT} (ver {@link CallPolicy}) e quando ela usa variáveis da Rotina que a declara, que a cópia não
     * tem: aí é interpretada sem que a camada de debug pare nela. O Pré-check da Rotina já passou pelo corpo dela.
     */
    private static Callee subroutineCallee(Subroutine subroutine, CallSites.Form form, boolean steppedInto,
            Frame caller) {
        Frame owner = caller.subroutineOwner();
        boolean onServer = !SubroutineScopes.usingParentVariables(owner.routine()).contains(subroutine);
        if (!steppedInto && form != CallSites.Form.FOR_SELECT && onServer) {
            return null;
        }
        return new Callee(subroutine.routine(), steppedInto, owner,
                SubroutineScopes.parentVariables(owner.routine(), subroutine));
    }

    /** Rotina privada da package de quem chama: no servidor, fora da package, ela não existe. */
    private boolean isPrivate(String packageName, String name, Frame caller) throws SQLException {
        String callerPackage = caller.routine().packageName();
        return callerPackage != null && (packageName == null || packageName.equals(callerPackage))
                && catalog.privateRoutines(callerPackage).contains(name);
    }

    /** {@code FOR EXECUTE STATEMENT}: como o {@code FOR SELECT}, mas não muda o {@code ROW_COUNT}. */
    private Jump executeLoop(ForExecuteStatement loop, Frame frame) throws SQLException {
        ExecuteStatement statement = loop.statement();
        refuse(statement.unsupported());
        try (Cursor cursor = new Cursor(null, false, null, loop.position())) {
            dml.open(cursor, statement, frame);
            frame.enterLoop(cursor);
            try {
                for (List<Object> row = next(cursor); row != null; row = next(cursor)) {
                    assignInto(statement.into(), dml.assignable(row, statement.into(), frame), frame);
                    Jump jump = execute(loop.body(), frame);
                    if (jump != null && !jump.targets(loop.label())) {
                        return jump;
                    }
                    if (jump != null && jump.kind() == Jump.Kind.LEAVE) {
                        break;
                    }
                    nextIteration(loop, frame);
                }
            } finally {
                frame.leaveLoop(cursor);
            }
        }
        return null;
    }

    private static List<Object> next(Cursor cursor) throws SQLException {
        return cursor.fetch(Fetch.Orientation.NEXT, null);
    }

    /**
     * {@code FETCH}: o {@code ROW_COUNT} passa a ser 1 se veio linha, senão 0. {@code FETCH RELATIVE 0} relê a linha
     * atual e a atribui, mas o motor não a conta.
     */
    private void fetch(Fetch fetch, Frame frame) throws SQLException {
        Cursor cursor = frame.cursor(fetch.cursor());
        Long offset = null;
        if (fetch.offset() != null) {
            Object value = evaluator.evaluate(fetch.offset(), frame, "BIGINT");
            offset = value == null ? null : ((Number) value).longValue();
        }
        List<Object> row = cursor.fetch(fetch.orientation(), offset);
        boolean reread = fetch.orientation() == Fetch.Orientation.RELATIVE && offset != null && offset == 0;
        frame.rowsFetched(row != null && !reread ? 1 : 0);
        if (row != null && !fetch.into().isEmpty()) {
            assignInto(fetch.into(), dml.assignable(row, fetch.into(), frame), frame);
        }
    }

    /**
     * {@code EXCEPTION nome ...} levanta a exceção pelo servidor; {@code EXCEPTION;} relança o erro do handler, e
     * não faz nada se não houver erro (fora de handler, ou num {@code WHEN ANY} que veio depois de outro handler
     * que casou), como no motor.
     */
    private void raise(Raise raise, Frame frame) throws SQLException {
        if (raise.exception() != null) {
            throw exceptions.raise(raise, frame);
        }
        SQLException handled = frame.handledError().error();
        if (handled != null) {
            throw handled;
        }
    }

    /**
     * {@code SUSPEND}: {@code NULL} numa saída {@code NOT NULL} é erro, que um {@code WHEN} pode tratar. Na
     * selecionável, a linha é emitida e a Rotina continua; o que ela alterou até aqui não é mais desfeito
     * ({@link SavepointStack#suspended()}). Num {@code EXECUTE PROCEDURE}, termina a Rotina como um {@code EXIT}.
     */
    private static Jump suspend(Frame frame) throws SQLException {
        List<Object> row = frame.outputValues();
        if (frame.rows() == null) {
            return Jump.EXIT;
        }
        frame.savepoints().suspended();
        frame.rows().accept(Collections.unmodifiableList(row));
        frame.savepoints().resumed();
        return null;
    }

    /**
     * {@code RETURN}: o valor é convertido para o tipo de retorno, como numa atribuição, e termina a function.
     * {@code NULL} num retorno {@code NOT NULL} é erro ali mesmo, que um {@code WHEN} pode tratar.
     */
    private Jump returnValue(Return statement, Frame frame) throws SQLException {
        ParameterDeclaration declared = frame.returnValue();
        if (declared == null) {
            throw new PsqlExecutionException(statement.position() + ": RETURN fora de function");
        }
        Object value = evaluator.evaluate(statement.value(), frame, declared.type());
        if (value == null && declared.notNull()) {
            throw ServerErrors.nullReturned();
        }
        frame.returned(value);
        return Jump.EXIT;
    }

    /**
     * Valor que a function devolve: o do {@code RETURN}, ou {@code NULL} se ela terminou sem {@code RETURN}. Nesse
     * caso, um retorno {@code NOT NULL} é erro, fora do alcance de qualquer {@code WHEN}.
     */
    private static Object returned(Frame frame) throws SQLException {
        Object value = frame.returned();
        if (value == null && frame.returnValue().notNull()) {
            throw ServerErrors.nullReturned();
        }
        return value;
    }

    /** @param row linha já convertida para os tipos dos alvos, ou {@code null} se não veio linha */
    private static void assignInto(List<String> targets, List<Object> row, Frame frame) throws SQLException {
        if (row == null) {
            return; // sem linha, o motor deixa os alvos como estavam
        }
        for (int i = 0; i < targets.size(); i++) {
            store(frame.get(targets.get(i)), row.get(i), frame);
        }
    }

    /** O Pré-check recusa estas construções antes do launch; aqui é a garantia de nunca executá-las. */
    private static void refuse(List<UnsupportedUse> unsupported) {
        if (!unsupported.isEmpty()) {
            UnsupportedUse use = unsupported.get(0);
            throw new PsqlExecutionException(use.position() + ": " + use.construct().syntax()
                    + " não é suportado pelo debugger");
        }
    }

    private void assign(Variable target, Expression value, Frame frame) throws SQLException {
        store(target, evaluator.evaluate(value, frame, target.type()), frame);
    }

    private static void store(Variable target, Object value, Frame frame) throws SQLException {
        if (value == null && target.notNull()) {
            throw ServerErrors.nullInNotNullVariable(target.name());
        }
        frame.assign(target, value);
    }

    /**
     * Desvio de fluxo que sobe pelos statements até quem o trata: o laço alvo, para {@code LEAVE} e
     * {@code CONTINUE}, ou a Rotina, para {@code EXIT}. O Firebird só compila {@code LEAVE}/{@code CONTINUE}
     * dentro de laço e com label existente, então todo desvio tem quem o trate.
     *
     * @param label label do laço alvo, ou {@code null} para o laço mais interno
     */
    private record Jump(Kind kind, String label) {

        enum Kind {
            LEAVE, CONTINUE, EXIT
        }

        static final Jump EXIT = new Jump(Kind.EXIT, null);

        boolean targets(String loopLabel) {
            return kind != Kind.EXIT && (label == null || label.equals(loopLabel));
        }
    }
}
