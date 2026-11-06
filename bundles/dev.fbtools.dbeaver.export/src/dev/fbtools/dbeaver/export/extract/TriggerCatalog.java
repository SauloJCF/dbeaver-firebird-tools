package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Triggers de tabela, de banco e DDL lidos de {@code RDB$TRIGGERS} (ADR 0007). Ficam de fora os de sistema e os que
 * o servidor cria para as constraints {@code CHECK}, que saem com a tabela. O tipo ({@code RDB$TRIGGER_TYPE}) é uma
 * máscara de bits: nos de tabela, o bit 0 é a fase (1 = {@code BEFORE}) e três pares de bits trazem até três eventos;
 * nos de banco, {@code 8192 + evento}; nos DDL, o bit 14 marca o tipo, o bit 0 é a fase (1 = {@code AFTER}) e o bit
 * {@code n} liga o evento {@code n}.
 */
final class TriggerCatalog {

    private static final String TRIGGERS = """
            SELECT t.RDB$TRIGGER_NAME, t.RDB$RELATION_NAME, t.RDB$TRIGGER_TYPE, t.RDB$TRIGGER_SEQUENCE,
                   t.RDB$TRIGGER_INACTIVE, t.RDB$TRIGGER_SOURCE, t.RDB$SQL_SECURITY, t.RDB$ENGINE_NAME,
                   t.RDB$ENTRYPOINT
              FROM RDB$TRIGGERS t
             WHERE COALESCE(t.RDB$SYSTEM_FLAG, 0) = 0
               AND NOT EXISTS (SELECT 1 FROM RDB$CHECK_CONSTRAINTS c WHERE c.RDB$TRIGGER_NAME = t.RDB$TRIGGER_NAME)
             ORDER BY t.RDB$TRIGGER_NAME""";

    private static final long DDL_FLAG = 0x4000L;
    private static final long DATABASE_FLAG = 0x2000L;
    // Todos os eventos ligados e a fase apagada ou não: é o "ANY DDL STATEMENT".
    private static final long ANY_DDL = 0x7FFFFFFFFFFFDFFFL;

    private static final List<String> DATABASE_EVENTS = List.of("CONNECT", "DISCONNECT", "TRANSACTION START",
            "TRANSACTION COMMIT", "TRANSACTION ROLLBACK");
    private static final String[] TABLE_ACTIONS = {null, "INSERT", "UPDATE", "DELETE"};
    private static final String[] DDL_EVENTS = new String[48];

    static {
        String[] kinds = {"TABLE", "PROCEDURE", "FUNCTION", "TRIGGER"};
        for (int i = 0; i < kinds.length; i++) {
            DDL_EVENTS[1 + 3 * i] = "CREATE " + kinds[i];
            DDL_EVENTS[2 + 3 * i] = "ALTER " + kinds[i];
            DDL_EVENTS[3 + 3 * i] = "DROP " + kinds[i];
        }
        String[] more = {"EXCEPTION", "VIEW", "DOMAIN", "ROLE", "INDEX", "SEQUENCE", "USER"};
        for (int i = 0; i < more.length; i++) {
            DDL_EVENTS[16 + 3 * i] = "CREATE " + more[i];
            DDL_EVENTS[17 + 3 * i] = "ALTER " + more[i];
            DDL_EVENTS[18 + 3 * i] = "DROP " + more[i];
        }
        DDL_EVENTS[37] = "CREATE COLLATION";
        DDL_EVENTS[38] = "DROP COLLATION";
        DDL_EVENTS[39] = "ALTER CHARACTER SET";
        DDL_EVENTS[40] = "CREATE PACKAGE";
        DDL_EVENTS[41] = "ALTER PACKAGE";
        DDL_EVENTS[42] = "DROP PACKAGE";
        DDL_EVENTS[43] = "CREATE PACKAGE BODY";
        DDL_EVENTS[44] = "DROP PACKAGE BODY";
        DDL_EVENTS[45] = "CREATE MAPPING";
        DDL_EVENTS[46] = "ALTER MAPPING";
        DDL_EVENTS[47] = "DROP MAPPING";
    }

    private final Connection connection;

    TriggerCatalog(Connection connection) {
        this.connection = connection;
    }

    /**
     * Um trigger. {@code table} é {@code null} nos de banco e DDL. {@code event} já vem no formato da sintaxe
     * ({@code BEFORE INSERT OR UPDATE}, {@code ON CONNECT}, {@code AFTER CREATE TABLE}, {@code BEFORE ANY DDL
     * STATEMENT}). {@code source} é a Fonte do banco, que já começa em {@code AS}; {@code engine} não nulo é um trigger
     * externo (UDR).
     */
    record Trigger(String name, String table, String event, int position, boolean inactive, String source,
            Boolean definer, String engine, String entryPoint) {

        boolean external() {
            return engine != null;
        }

        /** O {@code CREATE} (ou {@code CREATE OR ALTER}) inteiro, com a Fonte do banco sem tocar nela. */
        String toSql(ExtractionOptions options) {
            StringBuilder sql = new StringBuilder(options.createOrAlter() ? "CREATE OR ALTER TRIGGER " : "CREATE TRIGGER ")
                    .append(options.quote(name));
            if (table != null) {
                sql.append(" FOR ").append(options.quote(table));
            }
            sql.append('\n').append(inactive ? "INACTIVE " : "ACTIVE ").append(event).append(" POSITION ")
                    .append(position);
            if (definer != null) {
                sql.append("\nSQL SECURITY ").append(definer ? "DEFINER" : "INVOKER");
            }
            if (external()) {
                sql.append("\nEXTERNAL NAME '").append(entryPoint.replace("'", "''")).append("' ENGINE ").append(engine);
                return source == null || source.isBlank() ? sql.toString()
                        : sql.append("\nAS '").append(source.replace("'", "''")).append('\'').toString();
            }
            return sql.append(asSource(source)).toString();
        }
    }

    List<String> listTriggerNames() throws SQLException {
        List<String> names = new ArrayList<>();
        for (Trigger trigger : readTriggers(Selection.all(), ExtractionProgress.NONE)) {
            names.add(trigger.name());
        }
        return names;
    }

    List<Trigger> readTriggers(Selection selection, ExtractionProgress progress) throws SQLException {
        List<Trigger> triggers = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(TRIGGERS)) {
            while (rs.next()) {
                progress.checkCancelled();
                String name = rs.getString(1).strip();
                if (!selection.includesTrigger(name)) {
                    continue;
                }
                boolean definer = rs.getBoolean(7);
                Boolean security = rs.wasNull() ? null : definer;
                triggers.add(new Trigger(name, Rdb.trimmed(rs.getString(2)), event(rs.getLong(3)), rs.getInt(4),
                        rs.getInt(5) == 1, rs.getString(6), security, Rdb.trimmed(rs.getString(8)),
                        Rdb.trimmed(rs.getString(9))));
            }
        }
        return triggers;
    }

    /** O evento no formato da sintaxe, decodificado de {@code RDB$TRIGGER_TYPE}. */
    static String event(long type) {
        if ((type & DDL_FLAG) != 0) {
            String phase = (type & 1) == 1 ? "AFTER " : "BEFORE ";
            if ((type | 1) == ANY_DDL) {
                return phase + "ANY DDL STATEMENT";
            }
            List<String> events = new ArrayList<>();
            for (int bit = 1; bit < DDL_EVENTS.length; bit++) {
                if ((type & (1L << bit)) != 0 && DDL_EVENTS[bit] != null) {
                    events.add(DDL_EVENTS[bit]);
                }
            }
            return phase + String.join(" OR ", events);
        }
        if ((type & DATABASE_FLAG) != 0) {
            return "ON " + DATABASE_EVENTS.get((int) (type - DATABASE_FLAG));
        }
        List<String> actions = new ArrayList<>();
        for (int slot = 0; slot < 3; slot++) {
            int action = (int) (((type + 1) >> (1 + 2 * slot)) & 3);
            if (action != 0) {
                actions.add(TABLE_ACTIONS[action]);
            }
        }
        return ((type & 1) == 1 ? "BEFORE " : "AFTER ") + String.join(" OR ", actions);
    }

    /** O que vem depois do cabeçalho: a Fonte do banco, que já começa em {@code AS} (nula, só em catálogo estranho, vira corpo vazio). */
    private static String asSource(String source) {
        if (source == null) {
            return "\nAS\nBEGIN\nEND";
        }
        String text = source.stripLeading();
        boolean startsWithAs = text.length() >= 2 && text.substring(0, 2).equalsIgnoreCase("AS")
                && (text.length() == 2 || !Character.isLetterOrDigit(text.charAt(2)) && text.charAt(2) != '_');
        return startsWithAs ? "\n" + source.stripLeading() : "\nAS\n" + source;
    }
}
