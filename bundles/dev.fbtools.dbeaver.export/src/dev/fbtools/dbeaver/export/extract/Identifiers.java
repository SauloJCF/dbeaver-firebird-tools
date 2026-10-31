package dev.fbtools.dbeaver.export.extract;

import java.util.Set;
import java.util.regex.Pattern;

/** Nomes de objeto no script: entre aspas só quando preciso. */
public final class Identifiers {

    private static final Pattern PLAIN = Pattern.compile("[A-Z][A-Z0-9_$]*");

    /** Palavras reservadas do Firebird 5, as que obrigam aspas num identificador. */
    private static final Set<String> RESERVED = Set.of(
            "ADD", "ADMIN", "ALL", "ALTER", "AND", "ANY", "AS", "AT", "AVG", "BEGIN", "BETWEEN", "BIGINT", "BIT_LENGTH",
            "BLOB", "BOOLEAN", "BOTH", "BY", "CASE", "CAST", "CHAR", "CHARACTER", "CHARACTER_LENGTH", "CHAR_LENGTH",
            "CHECK", "CLOSE", "COLLATE", "COLUMN", "COMMENT", "COMMIT", "CONNECT", "CONSTRAINT", "CORR", "COUNT",
            "COVAR_POP", "COVAR_SAMP", "CREATE", "CROSS", "CURRENT", "CURRENT_CONNECTION", "CURRENT_DATE",
            "CURRENT_ROLE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_TRANSACTION", "CURRENT_USER", "CURSOR",
            "DATE", "DAY", "DEC", "DECFLOAT", "DECIMAL", "DECLARE", "DEFAULT", "DELETE", "DELETING", "DETERMINISTIC",
            "DISCONNECT", "DISTINCT", "DOUBLE", "DROP", "ELSE", "END", "ESCAPE", "EXECUTE", "EXISTS", "EXTERNAL",
            "EXTRACT", "FALSE", "FETCH", "FILTER", "FLOAT", "FOR", "FOREIGN", "FROM", "FULL", "FUNCTION", "GDSCODE",
            "GLOBAL", "GRANT", "GROUP", "HAVING", "HOUR", "IN", "INDEX", "INNER", "INSENSITIVE", "INSERT", "INSERTING",
            "INT", "INT128", "INTEGER", "INTO", "IS", "JOIN", "LATERAL", "LEADING", "LEFT", "LIKE", "LOCAL",
            "LOCALTIME", "LOCALTIMESTAMP", "LONG", "LOWER", "MAX", "MERGE", "MIN", "MINUTE", "MONTH", "NATIONAL",
            "NATURAL", "NCHAR", "NO", "NOT", "NULL", "NUMERIC", "OCTET_LENGTH", "OF", "OFFSET", "ON", "ONLY", "OPEN",
            "OR", "ORDER", "OUTER", "OVER", "PARAMETER", "PLAN", "POSITION", "POST_EVENT", "PRECISION", "PRIMARY",
            "PROCEDURE", "PUBLICATION", "READ", "REAL", "RECORD_VERSION", "RECREATE", "RECURSIVE", "REFERENCES",
            "REGR_AVGX", "REGR_AVGY", "REGR_COUNT", "REGR_INTERCEPT", "REGR_R2", "REGR_SLOPE", "REGR_SXX",
            "REGR_SXY", "REGR_SYY", "RELEASE", "RESETTING", "RETURN", "RETURNING_VALUES", "RETURNS", "REVOKE",
            "RIGHT", "ROLLBACK", "ROW", "ROWS", "ROW_COUNT", "SAVEPOINT", "SCROLL", "SECOND", "SELECT", "SENSITIVE",
            "SET", "SIMILAR", "SMALLINT", "SOME", "SQLCODE", "SQLSTATE", "START", "STDDEV_POP", "STDDEV_SAMP", "SUM",
            "TABLE", "THEN", "TIME", "TIMESTAMP", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TO", "TRAILING", "TRIGGER",
            "TRIM", "TRUE", "UNBOUNDED", "UNION", "UNIQUE", "UNKNOWN", "UPDATE", "UPDATING", "UPPER", "USER", "USING",
            "VALUE", "VALUES", "VARBINARY", "VARCHAR", "VARIABLE", "VARYING", "VAR_POP", "VAR_SAMP", "VIEW", "WHEN",
            "WHERE", "WHILE", "WINDOW", "WITH", "WITHOUT", "YEAR");

    private Identifiers() {
    }

    /** O nome como vai no script: sem aspas se for um identificador simples, entre aspas duplas caso contrário. */
    public static String quote(String name) {
        return needsQuotes(name) ? '"' + name.replace("\"", "\"\"") + '"' : name;
    }

    public static boolean needsQuotes(String name) {
        return !PLAIN.matcher(name).matches() || RESERVED.contains(name);
    }
}
