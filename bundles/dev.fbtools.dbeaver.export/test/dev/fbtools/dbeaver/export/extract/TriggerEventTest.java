package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Decodificação de {@code RDB$TRIGGER_TYPE}. Os valores vêm de triggers criados num Firebird 5 (sondagem de
 * 2026-11-03), não da documentação; não precisa de banco.
 */
class TriggerEventTest {

    @Test
    void singleTableEvents() {
        assertEquals("BEFORE INSERT", TriggerCatalog.event(1));
        assertEquals("AFTER INSERT", TriggerCatalog.event(2));
        assertEquals("BEFORE UPDATE", TriggerCatalog.event(3));
        assertEquals("AFTER UPDATE", TriggerCatalog.event(4));
        assertEquals("BEFORE DELETE", TriggerCatalog.event(5));
        assertEquals("AFTER DELETE", TriggerCatalog.event(6));
    }

    @Test
    void tableEventsWithMoreThanOneAction() {
        assertEquals("AFTER INSERT OR UPDATE OR DELETE", TriggerCatalog.event(114));
        assertEquals("BEFORE DELETE OR INSERT", TriggerCatalog.event(13));
        assertEquals("BEFORE INSERT OR UPDATE", TriggerCatalog.event(17));
    }

    @Test
    void databaseEvents() {
        assertEquals("ON CONNECT", TriggerCatalog.event(8192));
        assertEquals("ON DISCONNECT", TriggerCatalog.event(8193));
        assertEquals("ON TRANSACTION START", TriggerCatalog.event(8194));
        assertEquals("ON TRANSACTION COMMIT", TriggerCatalog.event(8195));
        assertEquals("ON TRANSACTION ROLLBACK", TriggerCatalog.event(8196));
    }

    @Test
    void ddlEvents() {
        assertEquals("AFTER CREATE TABLE OR ALTER TABLE OR DROP TABLE", TriggerCatalog.event(16399));
        assertEquals("AFTER CREATE PROCEDURE", TriggerCatalog.event(16401));
        assertEquals("BEFORE ANY DDL STATEMENT", TriggerCatalog.event(9223372036854767614L));
        assertEquals("AFTER ANY DDL STATEMENT", TriggerCatalog.event(9223372036854767615L));
        assertEquals("AFTER CREATE EXCEPTION", TriggerCatalog.event(81921));
        assertEquals("AFTER ALTER CHARACTER SET", TriggerCatalog.event(549755830273L));
        assertEquals("AFTER DROP MAPPING", TriggerCatalog.event(140737488371713L));
    }
}
