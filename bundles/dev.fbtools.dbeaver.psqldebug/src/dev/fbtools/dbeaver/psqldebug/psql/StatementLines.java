package dev.fbtools.dbeaver.psqldebug.psql;

import java.util.Comparator;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Consumer;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Block;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ForExecuteStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ForSelect;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Handler;
import dev.fbtools.dbeaver.psqldebug.psql.ast.If;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;
import dev.fbtools.dbeaver.psqldebug.psql.ast.While;

/**
 * Linhas em que o debugger pode parar: as que têm o início de um statement que não é bloco ({@code BEGIN}), no corpo
 * da Rotina e no das sub-rotinas dela. Um breakpoint numa linha sem statement vai para a próxima destas.
 */
public final class StatementLines {

    private StatementLines() {
    }

    /** @return as linhas, a partir de 1, do texto de que vêm as posições do AST */
    public static NavigableSet<Integer> of(RoutineDefinition routine) {
        NavigableSet<Integer> lines = new TreeSet<>();
        add(routine.body(), statement -> lines.add(statement.position().line()));
        for (Subroutine subroutine : routine.subroutines()) {
            add(subroutine.routine().body(), statement -> lines.add(statement.position().line()));
        }
        return lines;
    }

    /**
     * @return as posições dos statements em que o debugger pode parar, só os do corpo da Rotina (sem os das
     *         sub-rotinas), em ordem de posição
     */
    public static NavigableSet<SourcePosition> starts(RoutineDefinition routine) {
        NavigableSet<SourcePosition> starts = new TreeSet<>(
                Comparator.comparingInt(SourcePosition::line).thenComparingInt(SourcePosition::column));
        add(routine.body(), statement -> starts.add(statement.position()));
        return starts;
    }

    private static void add(Statement statement, Consumer<Statement> stops) {
        switch (statement) {
            case Block block -> {
                block.statements().forEach(inner -> add(inner, stops));
                block.handlers().stream().map(Handler::action).forEach(action -> add(action, stops));
                return; // o BEGIN não é onde o debugger para
            }
            case If branch -> {
                add(branch.thenBranch(), stops);
                if (branch.elseBranch() != null) {
                    add(branch.elseBranch(), stops);
                }
            }
            case While loop -> add(loop.body(), stops);
            case ForSelect loop -> add(loop.body(), stops);
            case ForExecuteStatement loop -> add(loop.body(), stops);
            default -> {
            }
        }
        stops.accept(statement);
    }
}
