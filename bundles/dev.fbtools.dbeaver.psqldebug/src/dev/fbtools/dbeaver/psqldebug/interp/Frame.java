package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import dev.fbtools.dbeaver.psqldebug.psql.Identifiers;
import dev.fbtools.dbeaver.psqldebug.psql.SubroutineScopes;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;

/**
 * Estado de uma Rotina em execução: a Rotina e o statement em execução, variáveis visíveis (parâmetros de entrada,
 * de saída e locais, na ordem declarada), cursores, o {@code ROW_COUNT}, o erro do handler em execução e os
 * savepoints dos blocos.
 */
public final class Frame {

    private final RoutineDefinition routine;
    private boolean steppedInto;
    /** Rotinas da package da Rotina, que uma chamada sem package dentro dela alcança. */
    private final Set<String> packageRoutines;
    /** Execução da Rotina que declara a sub-rotina deste frame; {@code null} fora de sub-rotina. */
    private final Frame parent;
    /** Variáveis de {@link #parent} que a sub-rotina enxerga. */
    private final Set<String> parentVariables;
    private final TemporaryPackages packages;
    private SourcePosition position;

    private final Map<String, Variable> variables = new LinkedHashMap<>();
    /** Cursores declarados e os nomeados dos laços em execução. */
    private final Map<String, Cursor> cursors = new HashMap<>();
    /** Cursores dos laços em execução, do mais interno para o mais externo. */
    private final Deque<Cursor> loops = new ArrayDeque<>();

    // ROW_COUNT, como o contador de linhas afetadas do motor: mostra as linhas alteradas pela última DML, ou, se a
    // última que zerou o contador foi uma leitura, as linhas lidas desde então.
    private boolean rowsWritten;
    private long rowsFetched;
    private long rowsModified;

    private ErrorStatus handledError = ErrorStatus.NONE;

    private final SavepointStack savepoints;
    private final Consumer<List<Object>> rows;
    private Object returned;

    /**
     * @param rows destino das linhas do {@code SUSPEND}, ou {@code null} se a Rotina roda como executável
     * @param steppedInto ver {@link #steppedInto()}
     * @param packageRoutines nomes das rotinas da package da Rotina; vazio fora de package
     * @param parent ver {@link #parent()}
     * @param parentVariables nomes das variáveis de {@code parent} que a sub-rotina enxerga (ver
     *            {@code SubroutineScopes})
     * @param packages onde ficam as cópias das sub-rotinas chamadas no servidor
     */
    Frame(RoutineDefinition routine, boolean steppedInto, SavepointStack savepoints, Consumer<List<Object>> rows,
            Set<String> packageRoutines, Frame parent, Set<String> parentVariables, TemporaryPackages packages) {
        this.routine = routine;
        this.steppedInto = steppedInto;
        this.savepoints = savepoints;
        this.rows = rows;
        this.packageRoutines = Set.copyOf(packageRoutines);
        this.parent = parent;
        this.parentVariables = Set.copyOf(parentVariables);
        this.packages = packages;
        this.position = routine.body().position();
    }

    /** A Rotina; numa sub-rotina, só ela, sem package (ver {@link #parent()}). */
    public RoutineDefinition routine() {
        return routine;
    }

    /**
     * Numa sub-rotina, a execução da Rotina que a declara, cujas variáveis ela lê e altera (ver
     * {@code SubroutineScopes}); {@code null} nos outros frames.
     */
    public Frame parent() {
        return parent;
    }

    /**
     * Rotina de cujo texto são as posições do frame: numa sub-rotina, a que a declara (ver {@link #parent()}); nos
     * outros frames, a própria Rotina.
     */
    public RoutineDefinition sourceRoutine() {
        return parent != null ? parent.routine : routine;
    }

    /** Frame da Rotina que declara as sub-rotinas que uma chamada sem package daqui alcança. */
    Frame subroutineOwner() {
        return parent != null ? parent : this;
    }

    /**
     * Sub-rotina alcançada daqui por uma chamada sem package: dentro da Rotina, as dela; dentro de uma sub-rotina, as
     * irmãs. A sub-rotina vem antes de qualquer outra Rotina com o mesmo nome, como no motor.
     *
     * @return {@code null} se não há sub-rotina com o tipo e o nome
     */
    Subroutine subroutine(RoutineDefinition.Kind kind, String name) {
        return subroutineOwner().routine.subroutine(kind, name);
    }

    /**
     * A camada de debug acompanha a Rotina: ela é a raiz, foi interpretada por step into, ou a execução pode parar
     * dentro dela (ver {@link CallPolicy#stopsInside}). Falso numa Rotina que o Interpretador interpreta por conta
     * própria num step over, porque no servidor a chamada não seria fiel ao motor ({@code FOR SELECT} sobre
     * procedure) ou não acharia a Rotina (privada de package); ver {@link CallPolicy}. A camada de debug não para
     * dentro dela. A procedure de um {@code FOR SELECT} pode passar a ser acompanhada numa volta seguinte do laço
     * ({@link #follow()}).
     */
    public boolean steppedInto() {
        return steppedInto;
    }

    /** Passa a ser acompanhada pela camada de debug (ver {@link #steppedInto()}), a partir do próximo statement. */
    void follow() {
        steppedInto = true;
    }

    /** Statement em execução (ou, numa Rotina que chama outra, o da chamada); antes do primeiro, o bloco principal. */
    public SourcePosition position() {
        return position;
    }

    void position(SourcePosition statement) {
        position = statement;
    }

    /**
     * Nome da procedure chamada para o servidor. Uma sub-rotina vai para a cópia dela no Package temporário de debug.
     * Dentro do corpo de uma package, uma rotina dela chamada sem package ganha o nome da package, como o motor a
     * resolve; fora dela, o nome sem package seria outra Rotina.
     */
    String serverName(String packageName, String name) throws SQLException {
        Subroutine subroutine = packageName == null ? subroutine(RoutineDefinition.Kind.PROCEDURE, name) : null;
        if (subroutine != null) {
            return Identifiers.quote(temporaryPackage(subroutine)) + "." + Identifiers.quote(name);
        }
        String pkg = packageName != null ? packageName : packageRoutines.contains(name) ? routine.packageName() : null;
        return (pkg == null ? "" : Identifiers.quote(pkg) + ".") + Identifiers.quote(name);
    }

    /**
     * Texto de um candidato a chamada numa expressão enviada ao servidor (ver {@link #serverName}). Com
     * {@code (}, pode ser function ou, depois de {@code FROM}, procedure; sem, só procedure.
     */
    String serverText(Expression.Call call) throws SQLException {
        if (call.packageName() == null) {
            Subroutine subroutine = call.arguments() ? subroutine(RoutineDefinition.Kind.FUNCTION, call.name())
                    : null;
            if (subroutine == null) {
                subroutine = subroutine(RoutineDefinition.Kind.PROCEDURE, call.name());
            }
            if (subroutine != null) {
                return Identifiers.quote(temporaryPackage(subroutine)) + "." + call.sql();
            }
        }
        if (call.packageName() == null && call.arguments() && packageRoutines.contains(call.name())) {
            return Identifiers.quote(routine.packageName()) + "." + call.sql();
        }
        return call.sql();
    }

    /** Package temporário com a cópia da sub-rotina. */
    private String temporaryPackage(Subroutine subroutine) throws SQLException {
        RoutineDefinition owner = subroutineOwner().routine;
        if (SubroutineScopes.usingParentVariables(owner).contains(subroutine)) {
            // O Pré-check recusa a chamada antes do launch; aqui é a garantia de nunca executá-la no servidor.
            throw new PsqlExecutionException(subroutine.position() + ": "
                    + UnsupportedConstruct.SUBROUTINE_USING_PARENT_VARIABLES.syntax() + " não é suportado pelo debugger");
        }
        return packages.packageFor(owner);
    }

    /** Tipo de retorno da function; {@code null} nos outros tipos de Rotina. */
    ParameterDeclaration returnValue() {
        return routine.returnValue();
    }

    /** Valor do {@code RETURN} executado; {@code null} antes dele. */
    Object returned() {
        return returned;
    }

    void returned(Object value) {
        returned = value;
    }

    /** Savepoints dos blocos em execução. */
    SavepointStack savepoints() {
        return savepoints;
    }

    /** Destino das linhas do {@code SUSPEND} numa procedure selecionável; {@code null} num {@code EXECUTE PROCEDURE}. */
    Consumer<List<Object>> rows() {
        return rows;
    }

    /**
     * Valores atuais dos parâmetros de saída, na ordem do {@code RETURNS}. Como no motor, {@code NULL} numa saída
     * {@code NOT NULL} é erro, citando a primeira.
     */
    List<Object> outputValues() throws SQLException {
        List<Object> values = new ArrayList<>();
        for (Variable variable : variables.values()) {
            if (variable.kind() == Variable.Kind.OUTPUT) {
                if (variable.value() == null && variable.notNull()) {
                    throw ServerErrors.nullInNotNullVariable(variable.name());
                }
                values.add(variable.value());
            }
        }
        return values;
    }

    Variable declare(String name, String type, boolean notNull, Variable.Kind kind) {
        Variable variable = new Variable(name, type, notNull, kind);
        if (variables.putIfAbsent(name, variable) != null) {
            throw new PsqlExecutionException("Variável declarada duas vezes: " + name);
        }
        return variable;
    }

    /**
     * Variável visível no frame: da Rotina ou, numa sub-rotina, da Rotina que a declara.
     *
     * @param name nome normalizado (ver {@code Identifiers.normalize})
     */
    public Optional<Variable> find(String name) {
        Variable variable = variables.get(name);
        if (variable == null && parent != null && parentVariables.contains(name)) {
            return parent.find(name);
        }
        return Optional.ofNullable(variable);
    }

    Variable get(String name) {
        return find(name).orElseThrow(() -> new PsqlExecutionException("Variável não declarada: " + name));
    }

    /** Variáveis da Rotina do frame; numa sub-rotina, sem as da Rotina que a declara. */
    public Collection<Variable> variables() {
        return Collections.unmodifiableCollection(variables.values());
    }

    /** {@code ROW_COUNT}: 0 antes da primeira Fatia DML da Rotina. */
    public long rowCount() {
        return rowsWritten ? rowsModified : rowsFetched;
    }

    /** {@code INSERT}/{@code UPDATE}/{@code DELETE}/{@code MERGE}: zera o contador e conta as linhas alteradas. */
    void rowsModified(long count) {
        rowsWritten = true;
        rowsModified = count;
        rowsFetched = 0;
    }

    /** {@code SELECT … INTO} e {@code FETCH}: zeram o contador e contam as linhas lidas. */
    void rowsFetched(long count) {
        rowsWritten = false;
        rowsModified = 0;
        rowsFetched = count;
    }

    /**
     * Linha lida por um {@code FOR SELECT}, que conta sem zerar: depois de uma DML no corpo, o {@code ROW_COUNT}
     * continua mostrando as linhas da DML; depois de um {@code FOR} interno, a contagem segue a dele.
     */
    void rowFetched() {
        rowsFetched++;
    }

    /** {@code FOR SELECT} ao abrir o cursor. */
    void clearRowCount() {
        rowsFetched(0);
    }

    void declareCursor(Cursor cursor) {
        if (cursors.putIfAbsent(cursor.name(), cursor) != null) {
            throw new PsqlExecutionException("Cursor declarado duas vezes: " + cursor.name());
        }
    }

    /**
     * Cursor de um {@code FOR SELECT}/{@code FOR EXECUTE STATEMENT} durante o laço. Se tiver nome
     * ({@code AS CURSOR}), os campos dele ficam visíveis.
     */
    void enterLoop(Cursor cursor) {
        loops.push(cursor);
        if (cursor.name() != null) {
            declareCursor(cursor);
        }
    }

    void leaveLoop(Cursor cursor) {
        loops.remove(cursor);
        if (cursor.name() != null) {
            cursors.remove(cursor.name());
        }
    }

    /**
     * Valor novo para a variável, que nenhum cursor aberto pode estar usando (ver {@link Cursor#checkUnchanged}):
     * nem os do frame, nem, se ela é da Rotina que declara a sub-rotina, os dessa Rotina.
     */
    void assign(Variable variable, Object value) {
        checkCursors(variable);
        if (parent != null && variables.get(variable.name()) != variable) {
            parent.checkCursors(variable);
        }
        variable.assign(value);
    }

    private void checkCursors(Variable variable) {
        for (Cursor cursor : cursors.values()) {
            cursor.checkUnchanged(variable);
        }
        for (Cursor cursor : loops) {
            cursor.checkUnchanged(variable);
        }
    }

    /** @param name nome normalizado */
    Optional<Cursor> findCursor(String name) {
        return Optional.ofNullable(cursors.get(name));
    }

    Cursor cursor(String name) {
        return findCursor(name).orElseThrow(() -> new PsqlExecutionException("Cursor não declarado: " + name));
    }

    /** Fecha os cursores que a Rotina deixou abertos, como o motor faz ao fim da execução. */
    void closeCursors() throws SQLException {
        SQLException failure = null;
        for (Cursor cursor : cursors.values()) {
            try {
                cursor.close();
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Erro do handler em execução, que dá {@code GDSCODE}/{@code SQLCODE}/{@code SQLSTATE}. */
    ErrorStatus handledError() {
        return handledError;
    }

    void handledError(ErrorStatus error) {
        handledError = error;
    }
}
