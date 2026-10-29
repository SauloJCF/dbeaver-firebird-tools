package dev.fbtools.dbeaver.psqldebug.psql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

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
import dev.fbtools.dbeaver.psqldebug.psql.ast.Raise;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Return;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Suspend;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedUse;
import dev.fbtools.dbeaver.psqldebug.psql.ast.While;

/**
 * Pré-check: varredura da Rotina antes do launch, que lista as construções não suportadas com linha e coluna. O
 * launch é recusado se a lista não estiver vazia. As construções recusadas estão em {@link UnsupportedConstruct}.
 */
public final class PreCheck {

    private PreCheck() {
    }

    /**
     * Construção recusada.
     *
     * @param description o que foi recusado, para mostrar a quem fez o launch
     */
    public record Finding(SourcePosition position, String description) {

        @Override
        public String toString() {
            return position + ": " + description;
        }
    }

    /**
     * @param text procedure, function ou {@code EXECUTE BLOCK} completo (ver {@link RoutineParser#parse})
     * @return construções recusadas em ordem de posição; texto que não parseia dá um único item, na posição do
     *         erro
     */
    public static List<Finding> check(String text) {
        RoutineDefinition procedure;
        try {
            procedure = RoutineParser.parse(text);
        } catch (PsqlSyntaxException e) {
            return List.of(unrecognized(e));
        }
        return scan(procedure);
    }

    /** Texto que o parser não reconhece: um item só, na posição do erro. */
    public static Finding unrecognized(PsqlSyntaxException error) {
        return new Finding(error.position(), "não suportado pelo debugger, " + error.detail());
    }

    /**
     * @return construções recusadas do AST, em ordem de posição, inclusive as dos corpos das sub-rotinas: a
     *         sub-rotina é parte da Fonte depurada e o step into entra nela
     */
    public static List<Finding> scan(RoutineDefinition procedure) {
        List<Finding> findings = new ArrayList<>();
        add(procedure.unsupported(), findings);
        for (CursorDeclaration cursor : procedure.cursors()) {
            add(cursor.select().unsupported(), findings);
        }
        scan(procedure.body(), findings);
        for (Subroutine subroutine : procedure.subroutines()) {
            findings.addAll(scan(subroutine.routine()));
        }
        add(subroutinesUsingParentVariables(procedure), findings);
        findings.sort(Comparator.comparing(Finding::position,
                Comparator.comparingInt(SourcePosition::line).thenComparingInt(SourcePosition::column)));
        return findings;
    }

    /**
     * Chamadas Executadas no servidor (em expressão ou Fatia DML) a sub-rotinas que usam variáveis da Rotina
     * ({@link UnsupportedConstruct#SUBROUTINE_USING_PARENT_VARIABLES}), na posição de cada chamada.
     */
    private static List<UnsupportedUse> subroutinesUsingParentVariables(RoutineDefinition procedure) {
        Set<String> names = new HashSet<>();
        SubroutineScopes.usingParentVariables(procedure).forEach(subroutine -> names.add(subroutine.routine().name()));
        List<UnsupportedUse> uses = new ArrayList<>();
        for (CallSites.CallSite site : CallSites.of(procedure)) {
            if (!site.stepIntoEligible() && site.packageName() == null && names.contains(site.name())) {
                uses.add(new UnsupportedUse(UnsupportedConstruct.SUBROUTINE_USING_PARENT_VARIABLES,
                        site.position()));
            }
        }
        return uses;
    }

    private static void scan(Statement statement, List<Finding> findings) {
        switch (statement) {
            case Block block -> {
                block.statements().forEach(inner -> scan(inner, findings));
                block.handlers().stream().map(Handler::action).forEach(action -> scan(action, findings));
            }
            case If branch -> {
                scan(branch.thenBranch(), findings);
                if (branch.elseBranch() != null) {
                    scan(branch.elseBranch(), findings);
                }
            }
            case While loop -> scan(loop.body(), findings);
            case ForSelect loop -> {
                add(loop.select().unsupported(), findings);
                add(cursorVariablesChanged(loop), findings);
                scan(loop.body(), findings);
            }
            case ForExecuteStatement loop -> {
                add(loop.statement().unsupported(), findings);
                scan(loop.body(), findings);
            }
            case DmlStatement dml -> add(dml.unsupported(), findings);
            case ExecuteStatement execute -> add(execute.unsupported(), findings);
            case ExecuteProcedure call -> {
            }
            case InAutonomousTransaction autonomous -> {
                add(List.of(new UnsupportedUse(UnsupportedConstruct.IN_AUTONOMOUS_TRANSACTION,
                        autonomous.position())), findings);
                scan(autonomous.body(), findings);
            }
            case Assignment assignment -> {
            }
            case Leave leave -> {
            }
            case Continue next -> {
            }
            case Exit exit -> {
            }
            case OpenCursor open -> {
            }
            case Fetch fetch -> {
            }
            case CloseCursor close -> {
            }
            case Raise raise -> {
            }
            case Suspend suspend -> {
            }
            case Return value -> {
            }
        }
    }

    /**
     * Atribuições, no corpo do {@code FOR SELECT} ou no {@code INTO} dele, a variáveis que o {@code SELECT} usa
     * ({@link UnsupportedConstruct#CURSOR_VARIABLE_CHANGED}), na posição de cada atribuição.
     */
    private static List<UnsupportedUse> cursorVariablesChanged(ForSelect loop) {
        Set<String> used = new HashSet<>();
        for (Expression.Part part : loop.select().sql().parts()) {
            if (part instanceof Expression.Name name) {
                used.add(name.name());
            } else if (part instanceof Expression.Field field) {
                used.add(field.qualifier() + "." + field.name()); // NEW.campo numa trigger
            }
        }
        List<UnsupportedUse> uses = new ArrayList<>();
        if (loop.select().into().stream().anyMatch(used::contains)) {
            uses.add(new UnsupportedUse(UnsupportedConstruct.CURSOR_VARIABLE_CHANGED, loop.select().position()));
        }
        assignments(loop.body(), (target, position) -> {
            if (used.contains(target)) {
                uses.add(new UnsupportedUse(UnsupportedConstruct.CURSOR_VARIABLE_CHANGED, position));
            }
        });
        return uses;
    }

    /** Cada variável que o statement (ou algum dentro dele) pode alterar, com a posição do statement. */
    private static void assignments(Statement statement, BiConsumer<String, SourcePosition> sink) {
        switch (statement) {
            case Block block -> {
                block.statements().forEach(inner -> assignments(inner, sink));
                block.handlers().forEach(handler -> assignments(handler.action(), sink));
            }
            case Assignment assignment -> sink.accept(assignment.target(), assignment.position());
            case If branch -> {
                assignments(branch.thenBranch(), sink);
                if (branch.elseBranch() != null) {
                    assignments(branch.elseBranch(), sink);
                }
            }
            case While loop -> assignments(loop.body(), sink);
            case ForSelect loop -> {
                loop.select().into().forEach(target -> sink.accept(target, loop.position()));
                assignments(loop.body(), sink);
            }
            case ForExecuteStatement loop -> {
                loop.statement().into().forEach(target -> sink.accept(target, loop.position()));
                assignments(loop.body(), sink);
            }
            case DmlStatement dml -> dml.into().forEach(target -> sink.accept(target, dml.position()));
            case ExecuteStatement execute -> execute.into().forEach(target -> sink.accept(target, execute.position()));
            case ExecuteProcedure call -> call.returningValues().forEach(target -> sink.accept(target, call.position()));
            case Fetch fetch -> fetch.into().forEach(target -> sink.accept(target, fetch.position()));
            case InAutonomousTransaction autonomous -> assignments(autonomous.body(), sink);
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
            case Raise raise -> {
            }
            case Suspend suspend -> {
            }
            case Return value -> {
            }
        }
    }

    private static void add(List<UnsupportedUse> uses, List<Finding> findings) {
        for (UnsupportedUse use : uses) {
            findings.add(new Finding(use.position(), use.construct().syntax() + " não é suportado pelo debugger"));
        }
    }
}
