package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Assignment;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Block;
import dev.fbtools.dbeaver.psqldebug.psql.ast.CloseCursor;
import dev.fbtools.dbeaver.psqldebug.psql.ast.CursorDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.DmlStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Fetch;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ForExecuteStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ForSelect;
import dev.fbtools.dbeaver.psqldebug.psql.ast.OpenCursor;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.VariableDeclaration;

/** {@code FOR SELECT}, {@code FOR EXECUTE STATEMENT} e cursores declarados no parser (issue 05). */
class CursorParserTest {

    @Test
    void forSelectSeparatesTheSelectTheTargetsAndTheCursorName() {
        ForSelect loop = (ForSelect) only("""
                L1: FOR SELECT ID, NOME AS "Nome" FROM T WHERE ID > :A
                    INTO :X, Y AS CURSOR "c" DO
                  X = X + 1;""");

        assertEquals("L1", loop.label());
        assertEquals("c", loop.cursor());
        assertEquals(List.of("X", "Y"), loop.select().into());
        assertEquals(DmlStatement.Kind.SELECT, loop.select().kind());
        assertEquals("SELECT ID, NOME AS \"Nome\" FROM T WHERE ID > ?",
                withPlaceholders(loop.select().sql()).strip());
        assertTrue(loop.body() instanceof Assignment);
        assertEquals(new SourcePosition(7, 3), loop.position());
    }

    @Test
    void forSelectWithoutIntoNeedsACursorName() {
        ForSelect loop = (ForSelect) only("FOR WITH Q AS (SELECT ID FROM T) SELECT ID FROM Q AS CURSOR C DO EXIT;");

        assertEquals("C", loop.cursor());
        assertEquals(List.of(), loop.select().into());
        assertNull(loop.label());

        PsqlSyntaxException error =
                assertThrows(PsqlSyntaxException.class, () -> only("FOR SELECT ID FROM T DO EXIT;"));
        assertEquals(new SourcePosition(7, 24), error.position());
    }

    @Test
    void cursorFieldsAreQualifiedNamesInPsqlAndOnlyWithColonInDml() {
        ForSelect loop = (ForSelect) only("""
                FOR SELECT ID FROM T AS CURSOR C DO
                BEGIN
                  X = C.ID + :C . "Valor" + F(C.ID) + T.C.ID + C.F(1);
                  INSERT INTO L (A, B) SELECT C.ID, :C.ID FROM T C;
                END""");
        List<Statement> body = ((Block) loop.body()).statements();

        Expression assignment = ((Assignment) body.get(0)).value();
        assertEquals(List.of(new Expression.Field("C", "ID", "C.ID"), new Expression.Field("C", "Valor", ":C . \"Valor\""),
                new Expression.Field("C", "ID", "C.ID")), fields(assignment));
        Expression insert = ((DmlStatement) body.get(1)).sql();
        assertEquals(List.of(new Expression.Field("C", "ID", ":C.ID")), fields(insert));
        assertTrue(insert.parts().stream().anyMatch(part -> part.sql().contains("SELECT C.ID, ")));
    }

    @Test
    void forExecuteStatementKeepsParametersAndTargets() {
        ForExecuteStatement loop = (ForExecuteStatement) only("""
                FOR EXECUTE STATEMENT ('SELECT ID FROM T WHERE ID > ?') (:A) INTO :X DO
                  LEAVE;""");

        assertEquals(List.of("X"), loop.statement().into());
        assertEquals(1, loop.statement().parameters().size());
        assertNull(loop.label());

        assertThrows(PsqlSyntaxException.class, () -> only("FOR EXECUTE STATEMENT 'DELETE FROM T' DO EXIT;"));
    }

    @Test
    void declaredCursorsWithAndWithoutScroll() {
        RoutineDefinition procedure = RoutineParser.parse("""
                CREATE PROCEDURE P (A INTEGER)
                AS
                DECLARE VARIABLE X INTEGER;
                DECLARE C1 CURSOR FOR (SELECT ID FROM T WHERE ID > :A AND (X IN (1, 2)));
                DECLARE C2 SCROLL CURSOR FOR (SELECT ID FROM T);
                DECLARE "c3" NO SCROLL CURSOR FOR (WITH Q AS (SELECT 1 AS N FROM RDB$DATABASE) SELECT N FROM Q);
                DECLARE Y INTEGER;
                BEGIN
                  OPEN C1;
                  CLOSE C1;
                END""");

        assertEquals(List.of("X", "Y"), procedure.declarations().stream().map(VariableDeclaration::name).toList());
        List<CursorDeclaration> cursors = procedure.cursors();
        assertEquals(List.of("C1", "C2", "c3"), cursors.stream().map(CursorDeclaration::name).toList());
        assertEquals(List.of(false, true, false), cursors.stream().map(CursorDeclaration::scroll).toList());
        assertEquals("SELECT ID FROM T WHERE ID > ? AND (X IN (1, 2))",
                withPlaceholders(cursors.get(0).select().sql()));
        assertEquals(new SourcePosition(4, 1), cursors.get(0).position());
        assertEquals("C1", ((OpenCursor) procedure.body().statements().get(0)).cursor());
        assertEquals("C1", ((CloseCursor) procedure.body().statements().get(1)).cursor());
    }

    @Test
    void fetchOrientations() {
        List<Statement> statements = statements("""
                  FETCH C INTO :X, Y;
                  FETCH C;
                  FETCH NEXT FROM C INTO X;
                  FETCH PRIOR FROM C;
                  FETCH FIRST FROM C INTO X;
                  FETCH LAST FROM C INTO X;
                  FETCH ABSOLUTE :X + (SELECT COUNT(*) FROM T) FROM C INTO X;
                  FETCH RELATIVE -1 FROM "c" INTO X;""");
        List<Fetch> fetches = statements.stream().map(Fetch.class::cast).toList();

        assertEquals(List.of(Fetch.Orientation.NEXT, Fetch.Orientation.NEXT, Fetch.Orientation.NEXT,
                Fetch.Orientation.PRIOR, Fetch.Orientation.FIRST, Fetch.Orientation.LAST, Fetch.Orientation.ABSOLUTE,
                Fetch.Orientation.RELATIVE), fetches.stream().map(Fetch::orientation).toList());
        assertEquals(List.of("X", "Y"), fetches.get(0).into());
        assertEquals(List.of(), fetches.get(1).into());
        assertEquals(":X + (SELECT COUNT(*) FROM T)", fetches.get(6).offset().text());
        assertEquals("-1", fetches.get(7).offset().text());
        assertEquals("c", fetches.get(7).cursor());
        assertNull(fetches.get(2).offset());

        assertThrows(PsqlSyntaxException.class, () -> only("FETCH SIDEWAYS FROM C;"));
        assertThrows(PsqlSyntaxException.class, () -> only("FETCH C X;"));
    }

    @Test
    void wordsOfCursorsAreReservedButFetchDirectionsAreNot() {
        Assignment assignment = (Assignment) only("NEXT = PRIOR + ABSOLUTE;");

        assertEquals("NEXT", assignment.target());
        assertFalse(assignment.value().parts().stream().anyMatch(Expression.Field.class::isInstance));
    }

    private static List<Expression.Field> fields(Expression expression) {
        return expression.parts().stream()
                .filter(Expression.Field.class::isInstance)
                .map(Expression.Field.class::cast)
                .toList();
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
                DECLARE Y INTEGER;
                BEGIN
                %s
                END""".formatted(body)).body().statements();
    }

    /** Texto como iria ao servidor se todos os candidatos fossem variáveis. */
    private static String withPlaceholders(Expression expression) {
        StringBuilder text = new StringBuilder();
        for (Expression.Part part : expression.parts()) {
            text.append(part instanceof Expression.Sql || part instanceof Expression.Call ? part.sql() : "?");
        }
        return text.toString();
    }
}
