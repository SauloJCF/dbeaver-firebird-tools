package dev.fbtools.dbeaver.psqldebug.psql;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

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
import dev.fbtools.dbeaver.psqldebug.psql.ast.Handler;
import dev.fbtools.dbeaver.psqldebug.psql.ast.If;
import dev.fbtools.dbeaver.psqldebug.psql.ast.InAutonomousTransaction;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Leave;
import dev.fbtools.dbeaver.psqldebug.psql.ast.OpenCursor;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Raise;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Return;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Suspend;
import dev.fbtools.dbeaver.psqldebug.psql.ast.VariableDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.While;

/**
 * Alcance das sub-rotinas no Firebird 5, conferido no servidor: a sub-rotina lê e altera os parâmetros de entrada e
 * de saída da Rotina que a declara e as variáveis locais declaradas antes dela. Não enxerga os cursores da Rotina,
 * nem, numa trigger, {@code NEW}/{@code OLD} e os eventos. Um parâmetro ou variável da própria sub-rotina com o mesmo
 * nome esconde o da Rotina.
 * <p>
 * Uma sub-rotina que usa essas variáveis não tem cópia fiel no Package temporário de debug, onde elas não existem
 * (ADR 0003, revisado): só o Interpretador a executa.
 */
public final class SubroutineScopes {

    private SubroutineScopes() {
    }

    /** Nomes normalizados das variáveis da Rotina que a sub-rotina enxerga (e não esconde com uma dela). */
    public static Set<String> parentVariables(RoutineDefinition parent, Subroutine subroutine) {
        Set<String> visible = new HashSet<>();
        if (parent.kind() != RoutineDefinition.Kind.TRIGGER) {
            parent.inputs().forEach(input -> visible.add(input.name()));
            parent.outputs().forEach(output -> visible.add(output.name()));
        }
        for (VariableDeclaration local : parent.declarations()) {
            if (before(local.position(), subroutine.position())) {
                visible.add(local.name());
            }
        }
        RoutineDefinition routine = subroutine.routine();
        routine.inputs().forEach(input -> visible.remove(input.name()));
        routine.outputs().forEach(output -> visible.remove(output.name()));
        routine.declarations().forEach(local -> visible.remove(local.name()));
        return visible;
    }

    /**
     * Sub-rotinas da Rotina que usam variáveis dela, direto ou chamando outra sub-rotina que usa. A comparação é por
     * nome e conservadora: um nome solto numa subconsulta de expressão pode ser coluna, e uma chamada a um nome de
     * sub-rotina conta qualquer que seja o tipo.
     *
     * @return conjunto por identidade
     */
    public static Set<Subroutine> usingParentVariables(RoutineDefinition parent) {
        Set<Subroutine> using = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Subroutine, Set<String>> calls = new IdentityHashMap<>();
        for (Subroutine subroutine : parent.subroutines()) {
            Set<String> names = new HashSet<>();
            references(subroutine.routine(), names);
            names.retainAll(parentVariables(parent, subroutine));
            if (!names.isEmpty()) {
                using.add(subroutine);
            }
            Set<String> called = new HashSet<>();
            for (CallSites.CallSite site : CallSites.of(subroutine.routine())) {
                if (site.packageName() == null) {
                    called.add(site.name());
                }
            }
            calls.put(subroutine, called);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Subroutine subroutine : parent.subroutines()) {
                if (!using.contains(subroutine) && using.stream()
                        .anyMatch(other -> calls.get(subroutine).contains(other.routine().name()))) {
                    using.add(subroutine);
                    changed = true;
                }
            }
        }
        return using;
    }

    private static boolean before(SourcePosition a, SourcePosition b) {
        return a.line() < b.line() || (a.line() == b.line() && a.column() < b.column());
    }

    /** Nomes que podem ser variáveis usadas pela Rotina: em expressões, Fatias DML e alvos de atribuição. */
    private static void references(RoutineDefinition routine, Set<String> names) {
        for (ParameterDeclaration input : routine.inputs()) {
            expression(input.defaultValue(), names);
        }
        for (VariableDeclaration local : routine.declarations()) {
            expression(local.initialValue(), names);
        }
        for (CursorDeclaration cursor : routine.cursors()) {
            expression(cursor.select().sql(), names);
        }
        statement(routine.body(), names);
    }

    private static void statement(Statement statement, Set<String> names) {
        switch (statement) {
            case Block block -> {
                block.statements().forEach(inner -> statement(inner, names));
                block.handlers().stream().map(Handler::action).forEach(action -> statement(action, names));
            }
            case Assignment assignment -> {
                names.add(assignment.target());
                expression(assignment.value(), names);
            }
            case If branch -> {
                expression(branch.condition(), names);
                statement(branch.thenBranch(), names);
                if (branch.elseBranch() != null) {
                    statement(branch.elseBranch(), names);
                }
            }
            case While loop -> {
                expression(loop.condition(), names);
                statement(loop.body(), names);
            }
            case ForSelect loop -> {
                expression(loop.select().sql(), names);
                names.addAll(loop.select().into());
                statement(loop.body(), names);
            }
            case ForExecuteStatement loop -> {
                executeStatement(loop.statement(), names);
                statement(loop.body(), names);
            }
            case DmlStatement dml -> {
                expression(dml.sql(), names);
                names.addAll(dml.into());
            }
            case ExecuteStatement execute -> executeStatement(execute, names);
            case ExecuteProcedure call -> {
                call.arguments().forEach(argument -> expression(argument, names));
                names.addAll(call.returningValues());
            }
            case Fetch fetch -> {
                expression(fetch.offset(), names);
                names.addAll(fetch.into());
            }
            case Raise raise -> {
                expression(raise.message(), names);
                raise.arguments().forEach(argument -> expression(argument, names));
            }
            case Return value -> expression(value.value(), names);
            case InAutonomousTransaction autonomous -> statement(autonomous.body(), names);
            case Leave leave -> {
            }
            case Continue next -> {
            }
            case Exit exit -> {
            }
            case OpenCursor open -> {
            }
            case CloseCursor close -> {
            }
            case Suspend suspend -> {
            }
        }
    }

    private static void executeStatement(ExecuteStatement execute, Set<String> names) {
        expression(execute.sql(), names);
        execute.parameters().forEach(parameter -> expression(parameter.value(), names));
        names.addAll(execute.into());
    }

    private static void expression(Expression expression, Set<String> names) {
        if (expression == null) {
            return;
        }
        for (Expression.Part part : expression.parts()) {
            if (part instanceof Expression.Name name) {
                names.add(name.name());
            }
        }
    }
}
