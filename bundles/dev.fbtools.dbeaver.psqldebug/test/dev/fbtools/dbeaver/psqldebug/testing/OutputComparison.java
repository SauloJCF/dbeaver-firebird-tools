package dev.fbtools.dbeaver.psqldebug.testing;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Compara o resultado da execução nativa com o do Interpretador e descreve cada divergência. */
public final class OutputComparison {

    private static final Pattern STACK_LINE = Pattern.compile("-?At (procedure|block|trigger|function|package) ");

    private OutputComparison() {
    }

    /**
     * @param call descrição da chamada, para a mensagem (ex.: {@code P(1, 'a')})
     * @throws AssertionError com uma linha {@code nome: nativo=... × interpretado=...} por saída divergente
     */
    public static void assertSame(String call, List<String> nativeNames, List<Object> nativeValues,
            List<String> interpretedNames, List<Object> interpretedValues) {
        if (!nativeNames.equals(interpretedNames)) {
            throw new AssertionError(call + ": parâmetros de saída diferentes: nativo=" + nativeNames
                    + " × interpretado=" + interpretedNames);
        }
        List<String> differences = new ArrayList<>();
        compareValues("", nativeNames, nativeValues, interpretedValues, differences);
        if (!differences.isEmpty()) {
            throw new AssertionError(call + ": " + differences.size()
                    + " saída(s) divergem entre a execução nativa e o Interpretador\n"
                    + String.join("\n", differences));
        }
    }

    /**
     * Mesmas linhas de {@code SUSPEND}, na mesma ordem.
     *
     * @throws AssertionError com uma linha {@code linha n, nome: nativo=... × interpretado=...} por valor
     *             divergente, e uma por linha que só um dos lados emitiu
     */
    public static void assertSameRows(String call, List<String> nativeNames, List<List<Object>> nativeRows,
            List<String> interpretedNames, List<List<Object>> interpretedRows) {
        if (!nativeNames.equals(interpretedNames)) {
            throw new AssertionError(call + ": colunas diferentes: nativo=" + nativeNames + " × interpretado="
                    + interpretedNames);
        }
        List<String> differences = new ArrayList<>();
        int common = Math.min(nativeRows.size(), interpretedRows.size());
        for (int i = 0; i < common; i++) {
            compareValues("linha " + (i + 1) + ", ", nativeNames, nativeRows.get(i), interpretedRows.get(i),
                    differences);
        }
        for (int i = common; i < nativeRows.size(); i++) {
            differences.add("  linha " + (i + 1) + " só no nativo: " + describeRow(nativeRows.get(i)));
        }
        for (int i = common; i < interpretedRows.size(); i++) {
            differences.add("  linha " + (i + 1) + " só no interpretado: " + describeRow(interpretedRows.get(i)));
        }
        if (!differences.isEmpty()) {
            throw new AssertionError(call + ": linhas divergem entre a execução nativa (" + nativeRows.size()
                    + " linha(s)) e o Interpretador (" + interpretedRows.size() + " linha(s))\n"
                    + String.join("\n", differences));
        }
    }

    private static void compareValues(String prefix, List<String> names, List<Object> expected,
            List<Object> actual, List<String> differences) {
        for (int i = 0; i < names.size(); i++) {
            if (!Objects.deepEquals(expected.get(i), actual.get(i))) {
                differences.add("  " + prefix + names.get(i) + ": nativo=" + describe(expected.get(i))
                        + " × interpretado=" + describe(actual.get(i)));
            }
        }
    }

    private static String describeRow(List<Object> row) {
        return row.stream().map(OutputComparison::describe).collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Mesma exceção dos dois lados, ou nenhuma: código ISC, SQLSTATE e mensagem do motor, sem a pilha de chamadas
     * ({@code At procedure ...}), que o Interpretador não reproduz.
     *
     * @param nativeError exceção da execução nativa, ou {@code null}
     * @param interpretedError exceção do Interpretador, ou {@code null}
     */
    public static void assertSameError(String call, SQLException nativeError, SQLException interpretedError) {
        if (nativeError == null && interpretedError == null) {
            return;
        }
        if (nativeError == null || interpretedError == null || nativeError.getErrorCode() != interpretedError
                .getErrorCode() || !Objects.equals(nativeError.getSQLState(), interpretedError.getSQLState())
                || !engineMessage(nativeError).equals(engineMessage(interpretedError))) {
            AssertionError error = new AssertionError(call + ": exceções divergem entre a execução nativa e o "
                    + "Interpretador\n  nativo=" + describeError(nativeError) + "\n  interpretado="
                    + describeError(interpretedError));
            if (interpretedError != null) {
                error.addSuppressed(interpretedError);
            }
            throw error;
        }
    }

    /**
     * Mesmas linhas em cada tabela.
     *
     * @param nativeTables linhas descritas de cada tabela, como {@link DifferentialHarness} as lê
     */
    public static void assertSameTables(String call, Map<String, List<String>> nativeTables,
            Map<String, List<String>> interpretedTables) {
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, List<String>> table : nativeTables.entrySet()) {
            List<String> expected = table.getValue();
            List<String> actual = interpretedTables.get(table.getKey());
            if (expected.equals(actual)) {
                continue;
            }
            List<String> missing = new ArrayList<>(expected);
            actual.forEach(missing::remove);
            List<String> extra = new ArrayList<>(actual);
            expected.forEach(extra::remove);
            differences.add("  " + table.getKey() + ": só no nativo=" + missing + " × só no interpretado=" + extra);
        }
        if (!differences.isEmpty()) {
            throw new AssertionError(call + ": " + differences.size()
                    + " tabela(s) divergem entre a execução nativa e o Interpretador\n"
                    + String.join("\n", differences));
        }
    }

    /**
     * Mensagem do motor sem o sufixo do Jaybird ({@code [SQLState:..., ISC error code:...]}) e sem as linhas
     * {@code At procedure/block/trigger/function ...}.
     */
    static String engineMessage(SQLException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        int suffix = message.lastIndexOf(" [SQLState:");
        if (suffix >= 0) {
            message = message.substring(0, suffix);
        }
        return Arrays.stream(message.split("; |\\R"))
                .map(String::strip)
                .filter(part -> !part.isEmpty() && !STACK_LINE.matcher(part).lookingAt())
                .collect(Collectors.joining("; "));
    }

    private static String describeError(SQLException error) {
        return error == null ? "sem exceção"
                : engineMessage(error) + " (ISC " + error.getErrorCode() + ", SQLSTATE " + error.getSQLState() + ")";
    }

    /** Valor com o tipo Java, e texto entre aspas para deixar espaços visíveis. */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        String text = switch (value) {
            case String s -> "'" + s + "'";
            case byte[] bytes -> "x'" + HexFormat.of().formatHex(bytes) + "'";
            default -> value.toString();
        };
        return text + " (" + value.getClass().getSimpleName() + ")";
    }
}
