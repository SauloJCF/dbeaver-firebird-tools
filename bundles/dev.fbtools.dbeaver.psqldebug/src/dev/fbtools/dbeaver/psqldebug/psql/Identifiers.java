package dev.fbtools.dbeaver.psqldebug.psql;

import java.util.Locale;

/** Regras de identificador do Firebird: regular é maiúsculo e sem aspas; entre aspas é literal. */
public final class Identifiers {

    private Identifiers() {
    }

    /** Nome como o Firebird o guarda nos metadados. */
    public static String normalize(String identifier) {
        if (identifier.length() >= 2 && identifier.startsWith("\"") && identifier.endsWith("\"")) {
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier.toUpperCase(Locale.ROOT);
    }

    /** Nome dos metadados como identificador entre aspas, que vale para qualquer nome. */
    public static String quote(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
