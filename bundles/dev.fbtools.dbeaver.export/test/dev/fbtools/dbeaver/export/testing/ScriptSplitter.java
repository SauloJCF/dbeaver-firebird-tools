package dev.fbtools.dbeaver.export.testing;

import java.util.ArrayList;
import java.util.List;

/**
 * Divide um script do isql em statements, respeitando {@code SET TERM}, comentários, literais e identificadores
 * entre aspas. Os {@code SET TERM} só trocam o terminador e não saem na lista; os comentários ficam no texto
 * (o {@link StatementNormalizer} os descarta).
 */
public final class ScriptSplitter {

    private ScriptSplitter() {
    }

    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        String terminator = ";";
        StringBuilder current = new StringBuilder();
        int length = script.length();
        int i = 0;
        while (i < length) {
            char c = script.charAt(i);
            if (script.startsWith(terminator, i)) {
                i += terminator.length();
                String text = current.toString().trim();
                current.setLength(0);
                String newTerminator = setTermTarget(text);
                if (newTerminator != null) {
                    terminator = newTerminator;
                } else if (!text.isEmpty()) {
                    statements.add(text);
                }
            } else if (c == '\'' || c == '"') {
                int end = closingQuote(script, i);
                current.append(script, i, end);
                i = end;
            } else if (script.startsWith("/*", i)) {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                current.append(script, i, end);
                i = end;
            } else if (script.startsWith("--", i)) {
                int end = script.indexOf('\n', i);
                end = end < 0 ? length : end;
                current.append(script, i, end);
                i = end;
            } else {
                current.append(c);
                i++;
            }
        }
        String rest = current.toString().trim();
        if (!rest.isEmpty()) {
            statements.add(rest);
        }
        return statements;
    }

    /** Posição logo depois da aspa que fecha o literal aberto em {@code start}; {@code ''} dentro dele é escape. */
    private static int closingQuote(String script, int start) {
        char quote = script.charAt(start);
        int i = start + 1;
        while (i < script.length()) {
            if (script.charAt(i) == quote) {
                if (i + 1 < script.length() && script.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return script.length();
    }

    private static String withoutLeadingComments(String text) {
        int i = 0;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else if (text.startsWith("--", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else {
                break;
            }
        }
        return text.substring(i);
    }

    /** O novo terminador se o texto for {@code SET TERM <novo>}; senão {@code null}. */
    private static String setTermTarget(String text) {
        String[] words = withoutLeadingComments(text).split("\\s+");
        if (words.length >= 3 && words[0].equalsIgnoreCase("SET") && words[1].equalsIgnoreCase("TERM")) {
            return words[2];
        }
        return null;
    }
}
