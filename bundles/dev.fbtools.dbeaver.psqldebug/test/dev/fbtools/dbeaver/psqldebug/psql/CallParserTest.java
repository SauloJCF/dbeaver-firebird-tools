package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Assignment;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ExecuteProcedure;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ForSelect;
import dev.fbtools.dbeaver.psqldebug.psql.ast.FunctionCall;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ProcedureSource;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;

/** Chamadas de Rotina no AST: {@code EXECUTE PROCEDURE}, {@code x = F(...)} e procedure como fonte de FOR SELECT. */
class CallParserTest {

    @Test
    void executeProcedureWithParenthesesAndReturningValues() {
        ExecuteProcedure call = assertInstanceOf(ExecuteProcedure.class,
                statement("EXECUTE PROCEDURE outra (1, :X + 1) RETURNING_VALUES :A, B;"));

        assertNull(call.packageName());
        assertEquals("OUTRA", call.name());
        assertEquals(List.of("1", ":X + 1"), texts(call.arguments()));
        assertEquals(List.of("A", "B"), call.returningValues());
        assertEquals(new SourcePosition(6, 3), call.position());
    }

    @Test
    void executeProcedureWithoutParentheses() {
        ExecuteProcedure call = assertInstanceOf(ExecuteProcedure.class,
                statement("EXECUTE PROCEDURE PKG.\"Sp\" (1) + 2, 'a' RETURNING_VALUES (:A);"));

        assertEquals("PKG", call.packageName());
        assertEquals("Sp", call.name());
        assertEquals(List.of("(1) + 2", "'a'"), texts(call.arguments())); // o ( não abre a lista: ela segue
        assertEquals(List.of("A"), call.returningValues());
    }

    @Test
    void executeProcedureWithoutArguments() {
        ExecuteProcedure bare = assertInstanceOf(ExecuteProcedure.class, statement("EXECUTE PROCEDURE P;"));
        ExecuteProcedure empty = assertInstanceOf(ExecuteProcedure.class,
                statement("EXECUTE PROCEDURE P RETURNING_VALUES :A;"));

        assertEquals(List.of(), bare.arguments());
        assertEquals(List.of(), bare.returningValues());
        assertEquals(List.of(), empty.arguments());
        assertEquals(List.of("A"), empty.returningValues());
    }

    @Test
    void assignmentWhoseWholeValueIsACall() {
        FunctionCall call = ((Assignment) statement("X = pkg.calcula(1, F(:Y, 2));")).call();

        assertEquals("PKG", call.packageName());
        assertEquals("CALCULA", call.name());
        assertEquals(List.of("1", "F(:Y, 2)"), texts(call.arguments()));
        assertEquals(new SourcePosition(6, 7), call.position());
        assertEquals(List.of(), ((Assignment) statement("X = F();")).call().arguments());
    }

    @Test
    void assignmentWithACallInsideALargerExpressionIsNotACall() {
        assertNull(((Assignment) statement("X = F(1) + 1;")).call());
        assertNull(((Assignment) statement("X = (F(1));")).call());
        assertNull(((Assignment) statement("X = F(1) || G(2);")).call());
        assertNull(((Assignment) statement("X = CAST(Y AS INTEGER);")).call()); // palavra reservada
        assertNull(((Assignment) statement("X = RDB$GET_CONTEXT('USER_SESSION', 'A');")).call());
    }

    @Test
    void forSelectWithTheProcedureAsTheOnlySource() {
        ProcedureSource source = ((ForSelect) statement(
                "FOR SELECT S.A, B AS \"Bê\" FROM pkg.sp(:X, 1) S INTO :A, :B DO EXIT;")).procedure();

        assertEquals("PKG", source.packageName());
        assertEquals("SP", source.name());
        assertEquals(List.of(":X", "1"), texts(source.arguments()));
        assertEquals(List.of(new ProcedureSource.Column("A", "A"), new ProcedureSource.Column("B", "Bê")),
                source.columns());

        ProcedureSource all = ((ForSelect) statement("FOR SELECT * FROM SP AS P AS CURSOR C DO EXIT;")).procedure();
        assertEquals("SP", all.name());
        assertEquals(List.of(), all.columns());
        assertEquals(List.of(), all.arguments());
    }

    @Test
    void forSelectWithOtherClausesOrExpressionsHasNoProcedureSource() {
        for (String select : List.of(
                "SELECT A FROM SP(1) WHERE A > 0 INTO :A",
                "SELECT A FROM SP(1) ORDER BY A INTO :A",
                "SELECT A FROM SP(1) ROWS 2 INTO :A",
                "SELECT DISTINCT A FROM SP(1) INTO :A",
                "SELECT FIRST 1 A FROM SP(1) INTO :A",
                "SELECT A + 1 FROM SP(1) INTO :A",
                "SELECT A, * FROM SP(1) INTO :A",
                "SELECT S.A FROM SP(1) S JOIN T ON T.ID = S.A INTO :A",
                "SELECT A FROM SP(1), T INTO :A",
                "SELECT A FROM SP(1) UNION SELECT 1 FROM RDB$DATABASE INTO :A",
                "SELECT 1 FROM RDB$DATABASE INTO :A",
                "WITH X AS (SELECT A FROM SP(1)) SELECT A FROM X INTO :A")) {
            assertNull(((ForSelect) statement("FOR " + select + " DO EXIT;")).procedure(), select);
        }
    }

    @Test
    void callCandidatesInsideExpressionsKeepTheText() {
        Expression value = ((Assignment) statement("X = 1 + pkg.f(:Y) + COALESCE(G(2), 0);")).value();

        List<Expression.Call> calls = value.parts().stream()
                .filter(Expression.Call.class::isInstance).map(Expression.Call.class::cast).toList();
        assertEquals(List.of("pkg.f", "COALESCE", "G"), calls.stream().map(Expression.Call::sql).toList());
        assertEquals("PKG", calls.get(0).packageName());
        assertEquals("F", calls.get(0).name());
        assertEquals("1 + pkg.f(:Y) + COALESCE(G(2), 0)",
                value.parts().stream().map(Expression.Part::sql).reduce("", String::concat));
    }

    private static Statement statement(String text) {
        return RoutineParser.parse("""
                CREATE PROCEDURE P
                AS
                DECLARE X INTEGER;
                DECLARE Y INTEGER;
                BEGIN
                  %s
                END""".formatted(text)).body().statements().get(0);
    }

    private static List<String> texts(List<Expression> expressions) {
        return expressions.stream().map(Expression::text).toList();
    }
}
