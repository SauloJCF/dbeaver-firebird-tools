package dev.fbtools.dbeaver.export.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * As Diferenças aceitas (ADR 0008): uma por linha, no formato {@code TIPO NOME | motivo}, ou {@code TIPO * | motivo}
 * para todos os objetos de um tipo. Linhas vazias e começando com {@code #} são ignoradas. A diferença aceita vale
 * para o objeto inteiro; o motivo é obrigatório, porque cada uma é uma decisão registrada.
 */
public final class AllowedDiffs {

    private final Map<String, String> reasons = new LinkedHashMap<>();
    private final Set<String> used = new TreeSet<>();

    public static AllowedDiffs none() {
        return new AllowedDiffs();
    }

    public static AllowedDiffs load(Path file) throws IOException {
        AllowedDiffs allowed = new AllowedDiffs();
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int bar = text.indexOf('|');
            String reason = bar < 0 ? "" : text.substring(bar + 1).strip();
            if (reason.isEmpty()) {
                throw new IllegalArgumentException(file + ":" + lineNumber + ": falta o motivo depois de '|'");
            }
            allowed.reasons.put(text.substring(0, bar).strip().toUpperCase(), reason);
        }
        return allowed;
    }

    /** Se a diferença neste objeto foi aceita; marca a entrada como usada. */
    public boolean allows(ObjectKey key) {
        String exact = (key.kind() + " " + key.name()).toUpperCase();
        String wildcard = key.kind().toUpperCase() + " *";
        for (String candidate : new String[] {exact, wildcard}) {
            if (reasons.containsKey(candidate)) {
                used.add(candidate);
                return true;
            }
        }
        return false;
    }

    /** Entradas que nenhuma diferença usou: ficaram velhas e devem sair do arquivo. */
    public Set<String> unused() {
        Set<String> unused = new TreeSet<>(reasons.keySet());
        unused.removeAll(used);
        return unused;
    }
}
