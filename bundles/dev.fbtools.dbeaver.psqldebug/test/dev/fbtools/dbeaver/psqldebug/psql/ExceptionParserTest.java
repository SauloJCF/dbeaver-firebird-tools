package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Assignment;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Block;
import dev.fbtools.dbeaver.psqldebug.psql.ast.DmlStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ErrorCondition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Handler;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Raise;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;

/** {@code EXCEPTION} e {@code WHEN} no parser (issue 07). */
class ExceptionParserTest {

    @Test
    void raiseForms() {
        List<Statement> statements = statements("""
                  EXCEPTION;
                  EXCEPTION E_SIMPLES;
                  EXCEPTION "e_Quoted" 'mensagem ' || :X;
                  EXCEPTION E_PARAM USING (1.50, X, (SELECT 1 FROM RDB$DATABASE));""");
        List<Raise> raises = statements.stream().map(Raise.class::cast).toList();

        assertNull(raises.get(0).exception());
        assertEquals("E_SIMPLES", raises.get(1).exception());
        assertNull(raises.get(1).message());
        assertEquals("e_Quoted", raises.get(2).exception());
        assertEquals("'mensagem ' || :X", raises.get(2).message().text());
        assertEquals(List.of("1.50", "X", "(SELECT 1 FROM RDB$DATABASE)"),
                raises.get(3).arguments().stream().map(Expression::text).toList());
        assertNull(raises.get(3).message());
        assertEquals(new SourcePosition(8, 1), raises.get(1).position());
    }

    @Test
    void exceptionIsNotReservedAndCanNameAVariable() {
        Assignment assignment = (Assignment) only("EXCEPTION = EXCEPTION + 1;");

        assertEquals("EXCEPTION", assignment.target());
        assertTrue(assignment.value().parts().contains(new Expression.Name("EXCEPTION", "EXCEPTION")));
    }

    @Test
    void handlersAtTheEndOfABlockWithEveryKindOfCondition() {
        Block block = (Block) only("""
                BEGIN
                  X = 1;
                  X = 2;
                WHEN EXCEPTION E_SIMPLES, EXCEPTION "e_q" DO X = 3;
                WHEN SQLCODE -803, SQLCODE 100, SQLCODE +1 DO X = 4;
                WHEN GDSCODE arith_except DO X = 5;
                WHEN SQLSTATE '23000' DO
                  BEGIN
                    X = 6;
                  WHEN ANY DO EXCEPTION;
                  END
                WHEN ANY DO X = 7;
                END""");

        assertEquals(2, block.statements().size());
        List<Handler> handlers = block.handlers();
        assertEquals(List.of(
                List.of(new ErrorCondition.UserException("E_SIMPLES"), new ErrorCondition.UserException("e_q")),
                List.of(new ErrorCondition.SqlCode(-803), new ErrorCondition.SqlCode(100),
                        new ErrorCondition.SqlCode(1)),
                List.of(new ErrorCondition.GdsCode("arith_except")),
                List.of(new ErrorCondition.SqlState("23000")),
                List.of(new ErrorCondition.AnyError())),
                handlers.stream().map(Handler::conditions).toList());
        Block nested = (Block) handlers.get(3).action();
        assertEquals(1, nested.handlers().size());
        assertTrue(nested.handlers().get(0).action() instanceof Raise);
        assertEquals(new SourcePosition(10, 1), handlers.get(0).position());
    }

    @Test
    void unknownConditionIsASyntaxError() {
        PsqlSyntaxException error = assertThrows(PsqlSyntaxException.class, () -> only("""
                BEGIN
                  X = 1;
                WHEN SQLCODE DO X = 2;
                END"""));

        assertEquals(new SourcePosition(9, 6), error.position());
        assertThrows(PsqlSyntaxException.class, () -> only("""
                BEGIN
                  X = 1;
                WHEN OTHERS DO X = 2;
                END"""));
    }

    @Test
    void errorContextVariablesAreCandidatesEvenInsideDml() {
        Block block = (Block) only("""
                BEGIN
                  X = 1;
                WHEN ANY DO
                  INSERT INTO LOG (G, S, T) VALUES (GDSCODE, SQLSTATE, :X || SQLCODE);
                END""");
        DmlStatement insert = (DmlStatement) block.handlers().get(0).action();

        assertEquals(List.of("GDSCODE", "SQLSTATE", "X", "SQLCODE"), insert.sql().parts().stream()
                .filter(Expression.Name.class::isInstance)
                .map(part -> ((Expression.Name) part).name())
                .toList());
    }

    private static Statement only(String statement) {
        List<Statement> statements = statements("  " + statement);
        assertEquals(1, statements.size());
        return statements.get(0);
    }

    private static List<Statement> statements(String body) {
        return RoutineParser.parse("""
                CREATE PROCEDURE P (A INTEGER)
                RETURNS (R INTEGER)
                AS
                DECLARE X INTEGER;
                DECLARE EXCEPTION INTEGER;
                BEGIN
                %s
                END""".formatted(body)).body().statements();
    }
}
