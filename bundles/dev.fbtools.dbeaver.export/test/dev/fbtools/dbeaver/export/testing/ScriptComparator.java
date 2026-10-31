package dev.fbtools.dbeaver.export.testing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compara dois scripts por objeto (ADR 0008): divide em statements, normaliza e agrupa por {@link ObjectKey}. A
 * ordem entre objetos não conta; dentro de um objeto, a ordem dos statements conta.
 */
public final class ScriptComparator {

    /** Uma divergência: o objeto e os statements normalizados de cada lado (lista vazia se o objeto não existe). */
    public record Difference(ObjectKey key, List<String> expected, List<String> actual) {

        @Override
        public String toString() {
            return key + "\n  esperado: " + expected + "\n  obtido:   " + actual;
        }
    }

    private ScriptComparator() {
    }

    /** Statements normalizados do script, agrupados por objeto, só dos tipos em {@code kinds} (todos se vazio). */
    public static Map<ObjectKey, List<String>> index(String script, Set<String> kinds) {
        Map<ObjectKey, List<String>> index = new LinkedHashMap<>();
        for (String statement : ScriptSplitter.split(script)) {
            String normalized = StatementNormalizer.normalize(statement);
            ObjectKey key = ObjectKey.of(normalized);
            if (key != null && (kinds.isEmpty() || kinds.contains(key.kind()))) {
                index.computeIfAbsent(key, k -> new ArrayList<>()).add(normalized);
            }
        }
        return index;
    }

    /** As divergências que {@code allowed} não aceita. */
    public static List<Difference> compare(String expected, String actual, Set<String> kinds, AllowedDiffs allowed) {
        Map<ObjectKey, List<String>> expectedIndex = index(expected, kinds);
        Map<ObjectKey, List<String>> actualIndex = index(actual, kinds);
        Set<ObjectKey> keys = new LinkedHashSet<>(expectedIndex.keySet());
        keys.addAll(actualIndex.keySet());
        List<Difference> differences = new ArrayList<>();
        for (ObjectKey key : keys) {
            List<String> expectedStatements = expectedIndex.getOrDefault(key, List.of());
            List<String> actualStatements = actualIndex.getOrDefault(key, List.of());
            if (!expectedStatements.equals(actualStatements) && !allowed.allows(key)) {
                differences.add(new Difference(key, expectedStatements, actualStatements));
            }
        }
        return differences;
    }
}
