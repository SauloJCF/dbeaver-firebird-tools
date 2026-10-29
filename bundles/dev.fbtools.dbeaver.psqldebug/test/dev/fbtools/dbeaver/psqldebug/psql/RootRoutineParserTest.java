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
import dev.fbtools.dbeaver.psqldebug.psql.ast.If;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Return;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;

/** Function, {@code EXECUTE BLOCK} e rotina de package como Rotina raiz (issue 09). */
class RootRoutineParserTest {

    private static final String PACKAGE_HEADER = """
            CREATE OR ALTER PACKAGE PKG
            AS
            BEGIN
              PROCEDURE SOMA (A INTEGER, B INTEGER = 10) RETURNS (TOTAL INTEGER);
              FUNCTION SOMA (A INTEGER = 1) RETURNS INTEGER;
            END""";

    private static final String PACKAGE_BODY = """
            RECREATE PACKAGE BODY PKG
            AS
            BEGIN
              FUNCTION SOMA (A INTEGER) RETURNS INTEGER
              AS
              BEGIN
                RETURN A + 1;
              END

              PROCEDURE SOMA (A INTEGER, B INTEGER) RETURNS (TOTAL INTEGER)
              AS
              DECLARE I INTEGER;
              BEGIN
                TOTAL = A + B;
                SUSPEND;
              END

              PROCEDURE PRIVADA (X INTEGER = 3)
              AS
              BEGIN
                X = X;
              END
            END""";

    @Test
    void functionHeaderKeepsTheReturnType() {
        RoutineDefinition function = RoutineParser.parse("""
                CREATE OR ALTER FUNCTION CALCULA (A NUMERIC(18,4), B VARCHAR(5) = 'x')
                RETURNS NUMERIC(18,2) NOT NULL DETERMINISTIC
                AS
                DECLARE V INTEGER;
                BEGIN
                  IF (A IS NULL) THEN RETURN 0;
                  RETURN A * V;
                END""");

        assertEquals(RoutineDefinition.Kind.FUNCTION, function.kind());
        assertEquals("CALCULA", function.displayName());
        assertEquals(List.of("A", "B"), function.inputs().stream().map(ParameterDeclaration::name).toList());
        assertEquals("'x'", function.inputs().get(1).defaultValue().text());
        assertTrue(function.outputs().isEmpty());
        assertEquals("NUMERIC(18,2)", function.returnValue().type());
        assertTrue(function.returnValue().notNull());
        If test = assertInstanceOf(If.class, function.body().statements().get(0));
        assertEquals("0", assertInstanceOf(Return.class, test.thenBranch()).value().text());
        Return last = assertInstanceOf(Return.class, function.body().statements().get(1));
        assertEquals(new SourcePosition(7, 3), last.position());
        assertEquals("A * V", last.value().text());
        assertFalse(function.selectable());
    }

    @Test
    void functionWithoutInputs() {
        RoutineDefinition function = RoutineParser.parse("""
                CREATE FUNCTION HOJE RETURNS TIMESTAMP WITH TIME ZONE AS
                BEGIN
                  RETURN CURRENT_TIMESTAMP;
                END""");

        assertTrue(function.inputs().isEmpty());
        assertEquals("TIMESTAMP WITH TIME ZONE", function.returnValue().type());
        assertFalse(function.returnValue().notNull());
    }

    @Test
    void executeBlockInputsAreBoundByTheClient() {
        RoutineDefinition block = RoutineParser.parse("""
                EXECUTE BLOCK (A INTEGER = ?, "b" VARCHAR(10) NOT NULL = ?)
                RETURNS (R INTEGER)
                AS
                DECLARE BLOCK INTEGER;
                DECLARE PACKAGE INTEGER;
                DECLARE BODY INTEGER;
                BEGIN
                  BLOCK = A;
                  R = BLOCK + PACKAGE + BODY;
                  SUSPEND;
                END""");

        assertEquals(RoutineDefinition.Kind.EXECUTE_BLOCK, block.kind());
        assertNull(block.name());
        assertEquals("EXECUTE BLOCK", block.displayName());
        assertEquals(List.of("A", "b"), block.inputs().stream().map(ParameterDeclaration::name).toList());
        assertNull(block.inputs().get(0).defaultValue());
        assertTrue(block.inputs().get(1).notNull());
        assertEquals("R", block.outputs().get(0).name());
        assertEquals("BLOCK", assertInstanceOf(Assignment.class, block.body().statements().get(0)).target());
        assertTrue(block.selectable());
    }

    @Test
    void executeBlockInputWithoutQuestionMarkIsRefused() {
        assertThrows(PsqlSyntaxException.class, () -> RoutineParser.parse("""
                EXECUTE BLOCK (A INTEGER = 5) AS
                BEGIN
                END"""));
    }

    @Test
    void packageRoutineComesFromTheBodyWithTheDefaultsOfTheHeader() {
        RoutineDefinition procedure = RoutineParser.parsePackageRoutine(PACKAGE_HEADER, PACKAGE_BODY,
                RoutineDefinition.Kind.PROCEDURE, "SOMA");

        assertEquals(RoutineDefinition.Kind.PROCEDURE, procedure.kind());
        assertEquals("PKG.SOMA", procedure.displayName());
        assertNull(procedure.inputs().get(0).defaultValue());
        assertEquals("10", procedure.inputs().get(1).defaultValue().text());
        // Posições do texto do corpo da package.
        assertEquals(new SourcePosition(10, 19), procedure.inputs().get(0).position());
        assertEquals(new SourcePosition(15, 5), procedure.body().statements().get(1).position());
        assertEquals("I", procedure.declarations().get(0).name());
        assertTrue(procedure.selectable());

        RoutineDefinition function = RoutineParser.parsePackageRoutine(PACKAGE_HEADER, PACKAGE_BODY,
                RoutineDefinition.Kind.FUNCTION, "SOMA");
        assertEquals(RoutineDefinition.Kind.FUNCTION, function.kind());
        assertEquals("1", function.inputs().get(0).defaultValue().text());
        assertEquals(new SourcePosition(7, 5), function.body().statements().get(0).position());
        assertFalse(function.selectable());
    }

    @Test
    void privatePackageRoutineHasOnlyTheBody() {
        RoutineDefinition procedure = RoutineParser.parsePackageRoutine(PACKAGE_HEADER, PACKAGE_BODY,
                RoutineDefinition.Kind.PROCEDURE, "PRIVADA");

        assertEquals("3", procedure.inputs().get(0).defaultValue().text());
    }

    @Test
    void packageBodyListsItsRoutinesWithTheLinesWhereEachOneIs() {
        List<RoutineParser.PackageRoutine> routines = RoutineParser.parsePackageBody(PACKAGE_BODY);

        assertEquals(List.of("PKG.SOMA", "PKG.SOMA", "PKG.PRIVADA"),
                routines.stream().map(routine -> routine.routine().displayName()).toList());
        assertEquals(RoutineDefinition.Kind.FUNCTION, routines.get(0).routine().kind());
        assertEquals(RoutineDefinition.Kind.PROCEDURE, routines.get(1).routine().kind());
        assertEquals(List.of(4, 10, 18), routines.stream().map(RoutineParser.PackageRoutine::firstLine).toList());
        assertEquals(List.of(8, 16, 22), routines.stream().map(RoutineParser.PackageRoutine::lastLine).toList());
        // Cada rotina é montada à parte: o SUSPEND de uma não a outra selecionável.
        assertFalse(routines.get(0).routine().selectable());
        assertTrue(routines.get(1).routine().selectable());
        assertFalse(routines.get(2).routine().selectable());
        assertEquals(new SourcePosition(15, 5), routines.get(1).routine().body().statements().get(1).position());
        // Sem o cabeçalho, sem os valores padrão dele.
        assertNull(routines.get(1).routine().inputs().get(1).defaultValue());
    }

    @Test
    void packageHeaderIsNotABody() {
        assertThrows(PsqlSyntaxException.class, () -> RoutineParser.parsePackageBody(PACKAGE_HEADER));
    }

    @Test
    void placedTextHasThePositionsOfTheDocument() {
        String block = """
                EXECUTE BLOCK RETURNS (R INTEGER)
                AS
                BEGIN
                  R = 1;
                END""";

        RoutineDefinition placed = RoutineParser.parse(RoutineParser.placed(block, 5, 9));

        // Na primeira linha, a coluna em que o bloco começa conta; nas outras, só a linha.
        assertEquals(new SourcePosition(5, 32), placed.outputs().get(0).position());
        assertEquals(new SourcePosition(8, 3), placed.body().statements().get(0).position());
        assertEquals(RoutineParser.parse(block).body().statements().get(0).position().column(),
                placed.body().statements().get(0).position().column());
    }

    @Test
    void missingPackageRoutineIsAnError() {
        assertThrows(PsqlSyntaxException.class, () -> RoutineParser.parsePackageRoutine(PACKAGE_HEADER,
                PACKAGE_BODY, RoutineDefinition.Kind.FUNCTION, "PRIVADA"));
    }
}
