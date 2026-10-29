package dev.fbtools.dbeaver.psqldebug.psql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
 * Chamadas de Rotina de uma Rotina, cada uma classificada como Step into elegível ou Executado no servidor. A
 * classificação é sintática: um nome seguido de {@code (} pode ser função interna ({@code UPPER(}), e um nome depois
 * de {@code FROM} numa Fatia DML pode ser tabela (ver {@link Expression.Call}). Só o banco diz quais são Rotinas.
 */
public final class CallSites {

    private CallSites() {
    }

    /** Onde a chamada está, o que decide se o step into entra nela. */
    public enum Form {
        /** {@code EXECUTE PROCEDURE}. */
        EXECUTE_PROCEDURE(true),
        /** {@code FOR SELECT … FROM sp(...)}, com a procedure como única fonte. */
        FOR_SELECT(true),
        /** {@code x = F(...)}, com a chamada como expressão inteira. */
        ASSIGNMENT(true),
        /** Dentro de uma expressão maior: roda no servidor, na Avaliação de expressão. */
        EXPRESSION(false),
        /** Dentro de uma Fatia DML. */
        DML(false);

        private final boolean stepIntoEligible;

        Form(boolean stepIntoEligible) {
            this.stepIntoEligible = stepIntoEligible;
        }
    }

    /**
     * Chamada de Rotina.
     *
     * @param packageName nome normalizado da package escrita na chamada, ou {@code null}
     * @param name nome normalizado da Rotina chamada
     * @param position posição do nome
     */
    public record CallSite(Form form, String packageName, String name, SourcePosition position) {

        /** Step into elegível; senão, Executado no servidor. */
        public boolean stepIntoEligible() {
            return form.stepIntoEligible;
        }

        /** {@code NOME} ou {@code PKG.NOME}, como escrito na chamada. */
        public String displayName() {
            return packageName == null ? name : packageName + "." + name;
        }

        @Override
        public String toString() {
            return position + ": " + displayName() + " (" + form + ")";
        }
    }

    /**
     * @return as chamadas em ordem de posição, inclusive as dos corpos das sub-rotinas (que estão no mesmo texto); uma
     *         chamada sem package pode ser de uma sub-rotina ({@link RoutineDefinition#subroutine})
     */
    public static List<CallSite> of(RoutineDefinition routine) {
        List<CallSite> sites = new ArrayList<>();
        for (Subroutine subroutine : routine.subroutines()) {
            sites.addAll(of(subroutine.routine()));
        }
        for (ParameterDeclaration input : routine.inputs()) {
            expression(input.defaultValue(), Form.EXPRESSION, null, sites);
        }
        for (VariableDeclaration local : routine.declarations()) {
            expression(local.initialValue(), Form.EXPRESSION, null, sites);
        }
        for (CursorDeclaration cursor : routine.cursors()) {
            expression(cursor.select().sql(), Form.DML, null, sites);
        }
        scan(routine.body(), sites);
        sites.sort(Comparator.comparing(CallSite::position,
                Comparator.comparingInt(SourcePosition::line).thenComparingInt(SourcePosition::column)));
        return sites;
    }

    private static void scan(Statement statement, List<CallSite> sites) {
        switch (statement) {
            case Block block -> {
                block.statements().forEach(inner -> scan(inner, sites));
                block.handlers().stream().map(Handler::action).forEach(action -> scan(action, sites));
            }
            case Assignment assignment -> {
                SourcePosition call = null;
                if (assignment.call() != null) {
                    call = assignment.call().position();
                    sites.add(new CallSite(Form.ASSIGNMENT, assignment.call().packageName(), assignment.call().name(),
                            call));
                }
                expression(assignment.value(), Form.EXPRESSION, call, sites);
            }
            case If branch -> {
                expression(branch.condition(), Form.EXPRESSION, null, sites);
                scan(branch.thenBranch(), sites);
                if (branch.elseBranch() != null) {
                    scan(branch.elseBranch(), sites);
                }
            }
            case While loop -> {
                expression(loop.condition(), Form.EXPRESSION, null, sites);
                scan(loop.body(), sites);
            }
            case ForSelect loop -> {
                SourcePosition source = null;
                if (loop.procedure() != null) {
                    source = loop.procedure().position();
                    sites.add(new CallSite(Form.FOR_SELECT, loop.procedure().packageName(), loop.procedure().name(),
                            source));
                }
                expression(loop.select().sql(), Form.DML, source, sites);
                scan(loop.body(), sites);
            }
            case ForExecuteStatement loop -> {
                executeStatement(loop.statement(), sites);
                scan(loop.body(), sites);
            }
            case DmlStatement dml -> expression(dml.sql(), Form.DML, null, sites);
            case ExecuteStatement execute -> executeStatement(execute, sites);
            case ExecuteProcedure call -> {
                sites.add(new CallSite(Form.EXECUTE_PROCEDURE, call.packageName(), call.name(), call.position()));
                call.arguments().forEach(argument -> expression(argument, Form.EXPRESSION, null, sites));
            }
            case Fetch fetch -> expression(fetch.offset(), Form.EXPRESSION, null, sites);
            case Raise raise -> {
                expression(raise.message(), Form.EXPRESSION, null, sites);
                raise.arguments().forEach(argument -> expression(argument, Form.EXPRESSION, null, sites));
            }
            case Return value -> expression(value.value(), Form.EXPRESSION, null, sites);
            case InAutonomousTransaction autonomous -> scan(autonomous.body(), sites);
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

    private static void executeStatement(ExecuteStatement execute, List<CallSite> sites) {
        expression(execute.sql(), Form.EXPRESSION, null, sites);
        execute.parameters().forEach(parameter -> expression(parameter.value(), Form.EXPRESSION, null, sites));
    }

    /**
     * Chamadas da expressão com a forma {@code form}.
     *
     * @param eligible posição da chamada elegível que a expressão contém, já anotada; ou {@code null}
     */
    private static void expression(Expression expression, Form form, SourcePosition eligible,
            List<CallSite> sites) {
        if (expression == null) {
            return;
        }
        for (Expression.Part part : expression.parts()) {
            if (part instanceof Expression.Call call && !call.position().equals(eligible)) {
                sites.add(new CallSite(form, call.packageName(), call.name(), call.position()));
            }
        }
    }
}
