package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.CallSites.CallSite;
import dev.fbtools.dbeaver.psqldebug.psql.CallSites.Form;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;

/** Sub-rotinas internas (issue 11): gramática, texto da cópia no package, alcance e Pré-check. */
class SubroutineParserTest {

    @Test
    void subroutinesAreParsedWithTheirOwnSignatureAndBody() {
        RoutineDefinition routine = RoutineParser.parse("""
                CREATE PROCEDURE P (A INTEGER) RETURNS (R INTEGER)
                AS
                  DECLARE FUNCTION DOBRO (X INTEGER) RETURNS INTEGER DETERMINISTIC
                  AS
                  BEGIN
                    RETURN X * 2;
                  END
                  DECLARE VARIABLE V INTEGER;
                  DECLARE PROCEDURE GERA (N INTEGER) RETURNS (I INTEGER)
                  AS
                    DECLARE K INTEGER = 0;
                  BEGIN
                    WHILE (K < N) DO
                    BEGIN
                      K = K + 1;
                      I = K;
                      SUSPEND;
                    END
                  END
                BEGIN
                  R = DOBRO(A);
                END""");

        assertEquals(List.of("V"), routine.declarations().stream().map(d -> d.name()).toList());
        assertFalse(routine.selectable(), "o SUSPEND é da sub-rotina, não da Rotina");
        assertEquals(2, routine.subroutines().size());

        Subroutine dobro = routine.subroutine(RoutineDefinition.Kind.FUNCTION, "DOBRO");
        assertNull(dobro.routine().packageName());
        assertEquals("INTEGER", dobro.routine().returnValue().type().strip());
        assertEquals(new SourcePosition(3, 3), dobro.position());
        assertEquals("FUNCTION DOBRO (X INTEGER) RETURNS INTEGER DETERMINISTIC\n  ", dobro.header());

        Subroutine gera = routine.subroutine(RoutineDefinition.Kind.PROCEDURE, "GERA");
        assertTrue(gera.routine().selectable());
        assertEquals(List.of("K"), gera.routine().declarations().stream().map(d -> d.name()).toList());
        assertTrue(gera.implementation().startsWith("PROCEDURE GERA (N INTEGER) RETURNS (I INTEGER)\n  AS\n"),
                gera.implementation());
        assertTrue(gera.implementation().endsWith("    END\n  END"), gera.implementation());
        assertNull(routine.subroutine(RoutineDefinition.Kind.FUNCTION, "GERA"));
    }

    @Test
    void defaultValuesGoToTheHeaderOnly() {
        RoutineDefinition routine = RoutineParser.parse("""
                EXECUTE BLOCK RETURNS (R INTEGER)
                AS
                  DECLARE FUNCTION ANTES (X INTEGER = 5, Y INTEGER = 1) RETURNS INTEGER;
                  DECLARE FUNCTION USA RETURNS INTEGER AS BEGIN RETURN ANTES(); END
                  DECLARE FUNCTION ANTES (X INTEGER, Y INTEGER) RETURNS INTEGER AS BEGIN RETURN X + Y; END
                  DECLARE PROCEDURE DIRETO (X INTEGER = 7) AS BEGIN END
                BEGIN
                  R = USA();
                  SUSPEND;
                END""");

        Subroutine antes = routine.subroutine(RoutineDefinition.Kind.FUNCTION, "ANTES");
        assertEquals(List.of("5", "1"),
                antes.routine().inputs().stream().map(input -> input.defaultValue().text()).toList());
        assertEquals("FUNCTION ANTES (X INTEGER = 5, Y INTEGER = 1) RETURNS INTEGER", antes.header());
        assertTrue(antes.implementation().startsWith("FUNCTION ANTES (X INTEGER, Y INTEGER) RETURNS INTEGER AS"));

        Subroutine direto = routine.subroutine(RoutineDefinition.Kind.PROCEDURE, "DIRETO");
        ParameterDeclaration x = direto.routine().inputs().get(0);
        assertEquals("7", x.defaultValue().text());
        assertEquals("PROCEDURE DIRETO (X INTEGER = 7) ", direto.header());
        assertEquals("PROCEDURE DIRETO (X INTEGER ) AS BEGIN END", direto.implementation());
        assertEquals(3, routine.subroutines().size());
    }

    @Test
    void subroutineInsideSubroutineIsRefused() {
        PsqlSyntaxException error = assertThrows(PsqlSyntaxException.class, () -> RoutineParser.parse("""
                CREATE PROCEDURE P
                AS
                  DECLARE FUNCTION A RETURNS INTEGER
                  AS
                    DECLARE FUNCTION B RETURNS INTEGER AS BEGIN RETURN 1; END
                  BEGIN
                    RETURN B();
                  END
                BEGIN
                END"""));

        assertEquals(new SourcePosition(5, 5), error.position());
    }

    @Test
    void refusedConstructsInsideASubroutineAreFoundByThePreCheck() {
        RoutineDefinition routine = RoutineParser.parse("""
                CREATE PROCEDURE P
                AS
                  DECLARE PROCEDURE S
                  AS
                  BEGIN
                    IN AUTONOMOUS TRANSACTION DO EXECUTE STATEMENT 'X';
                    BEGIN
                    WHEN ANY DO EXECUTE STATEMENT RDB$ERROR(MESSAGE);
                    END
                  END
                BEGIN
                  EXECUTE PROCEDURE S;
                END""");

        assertEquals(List.of(), routine.unsupported());
        assertEquals(List.of(new SourcePosition(6, 5), new SourcePosition(8, 35)),
                PreCheck.scan(routine).stream().map(PreCheck.Finding::position).toList());
    }

    @Test
    void callSitesIncludeTheCallsInsideSubroutines() {
        List<CallSite> sites = CallSites.of(RoutineParser.parse("""
                CREATE PROCEDURE P RETURNS (R INTEGER)
                AS
                  DECLARE FUNCTION S (X INTEGER) RETURNS INTEGER
                  AS
                  BEGIN
                    RETURN F_FORA(X);
                  END
                BEGIN
                  R = S(1);
                END"""));

        assertEquals(List.of(new CallSite(Form.EXPRESSION, null, "F_FORA", new SourcePosition(6, 12)),
                new CallSite(Form.ASSIGNMENT, null, "S", new SourcePosition(9, 7))), sites);
    }

    @Test
    void subroutineSeesTheParametersAndTheLocalsDeclaredBeforeIt() {
        RoutineDefinition routine = RoutineParser.parse("""
                CREATE PROCEDURE P (A INTEGER) RETURNS (R INTEGER)
                AS
                  DECLARE ANTES INTEGER;
                  DECLARE SOMBRA INTEGER;
                  DECLARE FUNCTION S (SOMBRA INTEGER) RETURNS INTEGER AS DECLARE R INTEGER; BEGIN RETURN 1; END
                  DECLARE DEPOIS INTEGER;
                BEGIN
                END""");

        assertEquals(Set.of("A", "ANTES"), SubroutineScopes.parentVariables(routine, routine.subroutines().get(0)));
    }

    @Test
    void triggerVariablesAreNotVisibleInsideSubroutines() {
        RoutineDefinition trigger = RoutineParser.parseTrigger("""
                CREATE TRIGGER T_BI FOR T BEFORE INSERT
                AS
                  DECLARE V INTEGER;
                  DECLARE FUNCTION S RETURNS INTEGER AS BEGIN RETURN 1; END
                BEGIN
                  NEW.X = S();
                END""", List.of(new RoutineParser.Column("X", "INTEGER")));

        assertEquals(Set.of("V"), SubroutineScopes.parentVariables(trigger, trigger.subroutines().get(0)));
    }

    @Test
    void usingParentVariablesIsTransitiveAndRespectsShadowing() {
        RoutineDefinition routine = RoutineParser.parse("""
                CREATE PROCEDURE P (A INTEGER) RETURNS (R INTEGER)
                AS
                  DECLARE V INTEGER;
                  DECLARE FUNCTION LE RETURNS INTEGER AS BEGIN RETURN V; END
                  DECLARE PROCEDURE ESCREVE AS BEGIN R = 1; END
                  DECLARE PROCEDURE DML AS BEGIN INSERT INTO T VALUES (:A); END
                  DECLARE FUNCTION VIA_IRMA RETURNS INTEGER AS BEGIN RETURN LE() + 1; END
                  DECLARE FUNCTION ESCONDE (V INTEGER) RETURNS INTEGER AS DECLARE R INTEGER; BEGIN R = V; RETURN R; END
                  DECLARE FUNCTION PURA (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X + COALESCE(NULL, 1); END
                BEGIN
                END""");

        assertEquals(Set.of("LE", "ESCREVE", "DML", "VIA_IRMA"), SubroutineScopes.usingParentVariables(routine)
                .stream().map(subroutine -> subroutine.routine().name()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void callInsideAnExpressionToASubroutineUsingParentVariablesIsRefused() {
        List<PreCheck.Finding> findings = PreCheck.check("""
                CREATE PROCEDURE P RETURNS (R INTEGER)
                AS
                  DECLARE V INTEGER = 1;
                  DECLARE FUNCTION LE RETURNS INTEGER AS BEGIN RETURN V; END
                  DECLARE FUNCTION PURA (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X; END
                BEGIN
                  R = LE();
                  R = PURA(LE());
                  R = CASE WHEN V > 0 THEN PURA(1) ELSE LE() END;
                  INSERT INTO T VALUES (LE());
                END""");

        String refused = UnsupportedConstruct.SUBROUTINE_USING_PARENT_VARIABLES.syntax()
                + " não é suportado pelo debugger";
        assertEquals(List.of(new PreCheck.Finding(new SourcePosition(8, 12), refused),
                new PreCheck.Finding(new SourcePosition(9, 41), refused),
                new PreCheck.Finding(new SourcePosition(10, 25), refused)), findings);
    }
}
