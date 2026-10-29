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
import dev.fbtools.dbeaver.psqldebug.psql.ast.DmlStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition.Trigger.Event;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;

/** Trigger isolada no AST: cabeçalho, {@code NEW}/{@code OLD} e eventos como entradas. */
class TriggerParserTest {

    @Test
    void headerWithForTablePhaseEventsAndPosition() {
        RoutineDefinition trigger = RoutineParser.parse("""
                CREATE OR ALTER TRIGGER tg FOR item ACTIVE BEFORE INSERT OR DELETE POSITION 3
                AS
                BEGIN
                END""");

        assertEquals(RoutineDefinition.Kind.TRIGGER, trigger.kind());
        assertEquals("TG", trigger.name());
        assertEquals(new RoutineDefinition.Trigger("ITEM", true, List.of(Event.INSERT, Event.DELETE)),
                trigger.trigger());
        assertEquals(List.of(), trigger.inputs());
    }

    @Test
    void sqlStandardHeaderWithOnTableAndSqlSecurity() {
        RoutineDefinition trigger = RoutineParser.parse("""
                CREATE TRIGGER TG INACTIVE AFTER UPDATE ON "Item" POSITION 0 SQL SECURITY DEFINER
                AS
                BEGIN
                END""");

        assertEquals(new RoutineDefinition.Trigger("Item", false, List.of(Event.UPDATE)), trigger.trigger());
        assertTrue(PreCheck.scan(trigger).isEmpty());
    }

    @Test
    void databaseAndDdlTriggersAreMarkedAsRefused() {
        assertEquals(List.of(new PreCheck.Finding(new SourcePosition(1, 25),
                UnsupportedConstruct.DATABASE_TRIGGER.syntax() + " não é suportado pelo debugger")),
                PreCheck.check("CREATE TRIGGER T ACTIVE ON TRANSACTION COMMIT POSITION 1 AS BEGIN END"));
        assertEquals(List.of(new PreCheck.Finding(new SourcePosition(1, 18),
                UnsupportedConstruct.DDL_TRIGGER.syntax() + " não é suportado pelo debugger")),
                PreCheck.check("CREATE TRIGGER T AFTER ANY DDL STATEMENT AS BEGIN END"));
        assertEquals(1, PreCheck.check("CREATE TRIGGER T BEFORE CREATE TABLE OR DROP PROCEDURE AS BEGIN END").size());
    }

    @Test
    void headerWithoutTableIsASyntaxError() {
        PsqlSyntaxException error = assertThrows(PsqlSyntaxException.class,
                () -> RoutineParser.parse("CREATE TRIGGER T BEFORE INSERT AS BEGIN END"));
        assertEquals(new SourcePosition(1, 18), error.position());
    }

    @Test
    void newFieldsAreAssignmentTargetsAndIntoTargets() {
        RoutineDefinition trigger = RoutineParser.parse("""
                CREATE TRIGGER TG FOR ITEM BEFORE INSERT
                AS
                BEGIN
                  NEW.NOME = UPPER(NEW.NOME);
                  NEW."Misto" = 1;
                  SELECT 1 FROM RDB$DATABASE INTO NEW.VALOR;
                END""");

        assertEquals("NEW.NOME", ((Assignment) trigger.body().statements().get(0)).target());
        assertEquals("NEW.Misto", ((Assignment) trigger.body().statements().get(1)).target());
        assertEquals(List.of("NEW.VALOR"), ((DmlStatement) trigger.body().statements().get(2)).into());
    }

    @Test
    void newOldAndEventsInsideDmlNeedNoColonInATrigger() {
        DmlStatement insert = (DmlStatement) RoutineParser.parse("""
                CREATE TRIGGER TG FOR ITEM AFTER UPDATE
                AS
                BEGIN
                  INSERT INTO LOG VALUES (OLD.ID, NEW.NOME, IIF(UPDATING, 'U', '?'), T.X);
                END""").body().statements().get(0);

        List<Expression.Part> references = insert.sql().parts().stream()
                .filter(part -> part instanceof Expression.Field || part instanceof Expression.Name).toList();
        assertEquals(List.of("OLD.ID", "NEW.NOME", "UPDATING"), references.stream().map(Expression.Part::sql).toList());

        // Fora de trigger, NEW.X numa Fatia DML é coluna de tabela (alias NEW).
        DmlStatement procedure = (DmlStatement) RoutineParser.parse("""
                CREATE PROCEDURE P AS
                BEGIN
                  INSERT INTO LOG SELECT NEW.ID, UPDATING FROM ITEM NEW;
                END""").body().statements().get(0);
        assertFalse(procedure.sql().parts().stream()
                .anyMatch(part -> part instanceof Expression.Field || part instanceof Expression.Name));
    }

    @Test
    void parseTriggerMakesNewOldAndEventsTheInputs() {
        RoutineDefinition trigger = RoutineParser.parseTrigger("""
                CREATE TRIGGER TG FOR ITEM BEFORE INSERT OR UPDATE
                AS
                BEGIN
                END""", List.of(new RoutineParser.Column("ID", "INTEGER"),
                new RoutineParser.Column("Nome", "VARCHAR(20)")));

        assertEquals(List.of("NEW.ID", "NEW.Nome", "OLD.ID", "OLD.Nome", "INSERTING", "UPDATING", "DELETING"),
                trigger.inputs().stream().map(ParameterDeclaration::name).toList());
        assertEquals(List.of("INTEGER", "VARCHAR(20)", "INTEGER", "VARCHAR(20)", "BOOLEAN", "BOOLEAN", "BOOLEAN"),
                trigger.inputs().stream().map(ParameterDeclaration::type).toList());
        assertEquals("NULL", trigger.inputs().get(0).defaultValue().text());
        assertEquals("FALSE", trigger.inputs().get(4).defaultValue().text());
        assertTrue(trigger.inputs().stream().noneMatch(ParameterDeclaration::notNull));
        assertNull(trigger.returnValue());
        assertInstanceOf(PsqlSyntaxException.class, assertThrows(PsqlSyntaxException.class,
                () -> RoutineParser.parseTrigger("CREATE PROCEDURE P AS BEGIN END", List.of())));
    }
}
