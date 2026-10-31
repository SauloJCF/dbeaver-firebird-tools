package dev.fbtools.dbeaver.export.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Normaliza um statement para comparação: sem comentários, espaços e quebras unificados, palavras em maiúsculas,
 * literais preservados e identificadores entre aspas sem aspas quando elas não mudam o nome. O resultado é a
 * lista de tokens separados por um espaço.
 */
public final class StatementNormalizer {

    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Z_][A-Z0-9_$]*");
    private static final List<String> TWO_CHAR_OPERATORS = List.of("||", "<>", "<=", ">=", "!=", "^=", "~=");

    private StatementNormalizer() {
    }

    public static String normalize(String statement) {
        List<String> tokens = new ArrayList<>();
        int length = statement.length();
        int i = 0;
        while (i < length) {
            char c = statement.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (statement.startsWith("/*", i)) {
                int end = statement.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (statement.startsWith("--", i)) {
                int end = statement.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (c == '\'') {
                int end = closingQuote(statement, i);
                tokens.add(statement.substring(i, end));
                i = end;
            } else if (c == '"') {
                int end = closingQuote(statement, i);
                String inner = statement.substring(i + 1, Math.max(i + 1, end - 1));
                tokens.add(PLAIN_IDENTIFIER.matcher(inner).matches() ? inner : statement.substring(i, end));
                i = end;
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int end = i + 1;
                while (end < length && (Character.isLetterOrDigit(statement.charAt(end))
                        || statement.charAt(end) == '_' || statement.charAt(end) == '$')) {
                    end++;
                }
                tokens.add(statement.substring(i, end).toUpperCase());
                i = end;
            } else if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < length && (Character.isLetterOrDigit(statement.charAt(end))
                        || statement.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(statement.substring(i, end).toUpperCase());
                i = end;
            } else {
                String two = i + 1 < length ? statement.substring(i, i + 2) : "";
                if (TWO_CHAR_OPERATORS.contains(two)) {
                    tokens.add(two);
                    i += 2;
                } else {
                    tokens.add(String.valueOf(c));
                    i++;
                }
            }
        }
        return String.join(" ", tokens);
    }

    private static int closingQuote(String text, int start) {
        char quote = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }
}
