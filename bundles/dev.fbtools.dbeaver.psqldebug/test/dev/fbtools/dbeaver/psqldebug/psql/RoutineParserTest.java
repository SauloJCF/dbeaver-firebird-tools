package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Assignment;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Block;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Continue;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Exit;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.If;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Leave;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Suspend;
import dev.fbtools.dbeaver.psqldebug.psql.ast.VariableDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.While;

class RoutineParserTest {

    private static final String SOURCE = """
            CREATE OR ALTER PROCEDURE calcula (A NUMERIC(18,4), "b" VARCHAR(10) CHARACTER SET UTF8 NOT NULL = 'x')
            RETURNS (TOTAL NUMERIC(18,4))
            AS
            DECLARE VARIABLE fator INTEGER = 2;
            DECLARE V2 VARCHAR(5) DEFAULT NULL;
            BEGIN
              TOTAL = A * fator;
              BEGIN
                V2 = SUBSTRING("b" FROM 1 FOR 2);
              END
            END""";

    @Test
    void headerKeepsParameterNamesAndDeclaredTypes() {
        RoutineDefinition procedure = RoutineParser.parse(SOURCE);

        assertEquals("CALCULA", procedure.name());
        ParameterDeclaration a = procedure.inputs().get(0);
        ParameterDeclaration b = procedure.inputs().get(1);
        assertEquals("A", a.name());
        assertEquals("NUMERIC(18,4)", a.type());
        assertFalse(a.notNull());
        assertNull(a.defaultValue());
        assertEquals("b", b.name());
        assertEquals("VARCHAR(10) CHARACTER SET UTF8", b.type());
        assertTrue(b.notNull());
        assertEquals("'x'", b.defaultValue().text());
        assertEquals("TOTAL", procedure.outputs().get(0).name());
        assertEquals("NUMERIC(18,4)", procedure.outputs().get(0).type());
    }

    @Test
    void declarationsAcceptOptionalVariableKeywordAndBothInitializers() {
        List<VariableDeclaration> declarations = RoutineParser.parse(SOURCE).declarations();

        assertEquals("FATOR", declarations.get(0).name());
        assertEquals("INTEGER", declarations.get(0).type());
        assertEquals("2", declarations.get(0).initialValue().text());
        assertEquals("V2", declarations.get(1).name());
        assertEquals("VARCHAR(5)", declarations.get(1).type());
        assertEquals("NULL", declarations.get(1).initialValue().text());
    }

    @Test
    void everyStatementHasLineAndColumn() {
        Block body = RoutineParser.parse(SOURCE).body();

        assertEquals(new SourcePosition(6, 1), body.position());
        Assignment total = assertInstanceOf(Assignment.class, body.statements().get(0));
        assertEquals(new SourcePosition(7, 3), total.position());
        Block inner = assertInstanceOf(Block.class, body.statements().get(1));
        assertEquals(new SourcePosition(8, 3), inner.position());
        assertEquals(new SourcePosition(9, 5), inner.statements().get(0).position());
        assertEquals(new SourcePosition(4, 1), RoutineParser.parse(SOURCE).declarations().get(0).position());
    }

    @Test
    void expressionSeparatesVariableCandidatesFromFunctionsAndQualifiedNames() {
        Expression expression = assignmentValue("""
                CREATE PROCEDURE P AS
                BEGIN
                  X = SUBSTRING("Nome" FROM :Inicio FOR 3) -- comentário
                      || T.COL || 'a:b';
                END""");

        List<Expression.Name> names = expression.parts().stream()
                .filter(Expression.Name.class::isInstance)
                .map(Expression.Name.class::cast)
                .toList();
        assertTrue(names.contains(new Expression.Name("Nome", "\"Nome\"")));
        assertTrue(names.contains(new Expression.Name("INICIO", ":Inicio")));
        assertTrue(names.stream().map(Expression.Name::name)
                .noneMatch(n -> List.of("SUBSTRING", "T", "COL").contains(n)));
        assertEquals("""
                SUBSTRING("Nome" FROM :Inicio FOR 3) -- comentário
                      || T.COL || 'a:b'""", expression.text());
        assertEquals(expression.text(),
                expression.parts().stream().map(Expression.Part::sql).reduce("", String::concat));
    }

    @Test
    void controlFlowStatementsKeepStructureLabelsAndPositions() {
        Block body = RoutineParser.parse("""
                CREATE PROCEDURE P AS
                BEGIN
                  IF (X IS NULL) THEN X = 1; ELSE IF (X > 0) THEN EXIT;
                  Laco: WHILE (:X < 10) DO
                  BEGIN
                    IF (X = 5) THEN
                      LEAVE laco;
                    CONTINUE;
                  END
                  WHILE (1 = 1) DO BREAK;
                END""").body();

        If outer = assertInstanceOf(If.class, body.statements().get(0));
        assertEquals(new SourcePosition(3, 3), outer.position());
        assertEquals("X IS NULL", outer.condition().text());
        assertInstanceOf(Assignment.class, outer.thenBranch());
        If elseIf = assertInstanceOf(If.class, outer.elseBranch());
        assertEquals(new SourcePosition(3, 35), elseIf.position());
        assertEquals(new Exit(new SourcePosition(3, 51)), elseIf.thenBranch());
        assertNull(elseIf.elseBranch());

        While loop = assertInstanceOf(While.class, body.statements().get(1));
        assertEquals("LACO", loop.label());
        assertEquals(new SourcePosition(4, 3), loop.position());
        assertTrue(loop.condition().parts().contains(new Expression.Name("X", ":X")));
        Block loopBody = assertInstanceOf(Block.class, loop.body());
        If leaveIf = assertInstanceOf(If.class, loopBody.statements().get(0));
        assertEquals(new Leave("LACO", new SourcePosition(7, 7)), leaveIf.thenBranch());
        assertEquals(new Continue(null, new SourcePosition(8, 5)), loopBody.statements().get(1));

        While unlabeled = assertInstanceOf(While.class, body.statements().get(2));
        assertNull(unlabeled.label());
        assertEquals(new Leave(null, new SourcePosition(10, 20)), unlabeled.body());
    }

    @Test
    void danglingElseBelongsToTheNearestIf() {
        If outer = (If) RoutineParser.parse("""
                CREATE PROCEDURE P AS
                BEGIN
                  IF (A > 0) THEN IF (B > 0) THEN X = 1; ELSE X = 2;
                END""").body().statements().get(0);

        assertNull(outer.elseBranch());
        assertInstanceOf(Assignment.class, assertInstanceOf(If.class, outer.thenBranch()).elseBranch());
    }

    @Test
    void nonReservedKeywordsWorkAsVariablesAndLabels() {
        Block body = RoutineParser.parse("""
                CREATE PROCEDURE P AS
                DECLARE VARIABLE IF INTEGER;
                DECLARE VARIABLE LEAVE INTEGER;
                BEGIN
                  DO: WHILE (LEAVE < IF) DO
                  BEGIN
                    LEAVE = LEAVE + EXIT;
                    CONTINUE DO;
                  END
                END""").body();

        While loop = assertInstanceOf(While.class, body.statements().get(0));
        assertEquals("DO", loop.label());
        Block loopBody = (Block) loop.body();
        Assignment assignment = assertInstanceOf(Assignment.class, loopBody.statements().get(0));
        assertEquals("LEAVE", assignment.target());
        assertTrue(assignment.value().parts().contains(new Expression.Name("EXIT", "EXIT")));
        assertEquals(new Continue("DO", new SourcePosition(8, 5)), loopBody.statements().get(1));
    }

    @Test
    void suspendIsAStatementAndAlsoAVariableName() {
        Block body = RoutineParser.parse("""
                CREATE PROCEDURE P RETURNS (SUSPEND INTEGER) AS
                BEGIN
                  SUSPEND = SUSPEND + 1;
                  SUSPEND;
                  WHILE (SUSPEND < 3) DO SUSPEND;
                END""").body();

        Assignment assignment = assertInstanceOf(Assignment.class, body.statements().get(0));
        assertEquals("SUSPEND", assignment.target());
        assertTrue(assignment.value().parts().contains(new Expression.Name("SUSPEND", "SUSPEND")));
        assertEquals(new Suspend(new SourcePosition(4, 3)), body.statements().get(1));
        While loop = assertInstanceOf(While.class, body.statements().get(2));
        assertEquals(new Suspend(new SourcePosition(5, 26)), loop.body());
    }

    @Test
    void syntaxErrorReportsLineAndColumn() {
        PsqlSyntaxException error = assertThrows(PsqlSyntaxException.class, () -> RoutineParser.parse("""
                CREATE PROCEDURE P AS
                BEGIN
                  X = 1;
                  FOO BAR;
                END"""));

        assertEquals(new SourcePosition(4, 7), error.position());
    }

    private static Expression assignmentValue(String source) {
        Assignment assignment = (Assignment) RoutineParser.parse(source).body().statements().get(0);
        return assignment.value();
    }
}
