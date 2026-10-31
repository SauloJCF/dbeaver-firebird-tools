package dev.fbtools.dbeaver.export.testing;

import java.util.Arrays;
import java.util.List;

/**
 * Objeto do banco a que um statement pertence, para comparar "por objeto" (ADR 0008): {@code kind} é o tipo
 * ({@code DOMAIN}, {@code GENERATOR}, {@code TABLE}, {@code CONSTRAINT}…) e {@code name} o nome sem aspas.
 * Statements que não definem um objeto ({@code SET NAMES}, {@code CREATE DATABASE}, {@code INSERT}…) não têm chave.
 */
public record ObjectKey(String kind, String name) {

    private static final List<String> IGNORED_PREFIXES = List.of(
            "SET SQL DIALECT", "SET NAMES", "SET CLIENTLIB", "CREATE DATABASE", "CONNECT", "COMMIT", "INSERT",
            "ROLLBACK");
    private static final List<String> CREATABLE = List.of(
            "DOMAIN", "EXCEPTION", "ROLE", "PROCEDURE", "FUNCTION", "TRIGGER", "VIEW", "TABLE", "PACKAGE BODY",
            "PACKAGE", "COLLATION", "FILTER", "EXTERNAL FUNCTION", "SHADOW", "INDEX");

    /** A chave do statement já normalizado ({@link StatementNormalizer}), ou {@code null} se for ignorado. */
    public static ObjectKey of(String normalized) {
        if (normalized.isEmpty() || IGNORED_PREFIXES.stream().anyMatch(normalized::startsWith)) {
            return null;
        }
        String[] words = normalized.split(" ");
        String head = words[0];
        if (head.equals("GRANT") || head.equals("REVOKE")) {
            return new ObjectKey("GRANT", normalized);
        }
        if (head.equals("COMMENT") && words.length > 3 && words[1].equals("ON")) {
            if (words[2].equals("COLUMN") && words.length > 5 && words[4].equals(".")) {
                return new ObjectKey("DESCRIPTION", "COLUMN " + words[3] + "." + words[5]);
            }
            return new ObjectKey("DESCRIPTION", words[2] + " " + words[3]);
        }
        if (head.equals("DESCRIBE") && words.length > 2) {
            return new ObjectKey("DESCRIPTION", describeTarget(words));
        }
        if (head.equals("SET") && words.length > 2 && words[1].equals("GENERATOR")) {
            return new ObjectKey("GENERATOR", words[2]);
        }
        if (head.equals("ALTER") && words.length > 5 && words[1].equals("TABLE") && words[3].equals("ADD")
                && words[4].equals("CONSTRAINT")) {
            return new ObjectKey("CONSTRAINT", words[5]);
        }
        if (head.equals("ALTER") && words.length > 3 && words[1].equals("TABLE")) {
            return new ObjectKey("TABLE", words[2]);
        }
        if (head.equals("CREATE") || head.equals("ALTER") || head.equals("RECREATE")) {
            return createdObject(words);
        }
        return new ObjectKey("OTHER", String.join(" ", Arrays.copyOf(words, Math.min(words.length, 3))));
    }

    private static ObjectKey createdObject(String[] words) {
        int from = 1;
        if (words.length > 3 && words[1].equals("OR") && words[2].equals("ALTER")) {
            from = 3;
        }
        List<String> rest = Arrays.asList(words).subList(from, words.length);
        if (!rest.isEmpty() && (rest.get(0).equals("GENERATOR") || rest.get(0).equals("SEQUENCE"))
                && rest.size() > 1) {
            return new ObjectKey("GENERATOR", rest.get(1));
        }
        int skipped = 0;
        while (skipped < rest.size() && List.of("UNIQUE", "ASC", "ASCENDING", "DESC", "DESCENDING")
                .contains(rest.get(skipped))) {
            skipped++;
        }
        List<String> tail = rest.subList(skipped, rest.size());
        for (String kind : CREATABLE) {
            String[] kindWords = kind.split(" ");
            if (tail.size() > kindWords.length && tail.subList(0, kindWords.length).equals(List.of(kindWords))) {
                return new ObjectKey(kind, tail.get(kindWords.length));
            }
        }
        return new ObjectKey("OTHER", String.join(" ", rest.subList(0, Math.min(rest.size(), 3))));
    }

    /** {@code DESCRIBE FIELD COL TABLE T} descreve a coluna; os demais são {@code DESCRIBE <tipo> <nome>}. */
    private static String describeTarget(String[] words) {
        if (words[1].equals("FIELD") && words.length > 4 && words[3].equals("TABLE")) {
            return "COLUMN " + words[4] + "." + words[2];
        }
        return words[1] + " " + words[2];
    }

    @Override
    public String toString() {
        return kind + " " + name;
    }
}
