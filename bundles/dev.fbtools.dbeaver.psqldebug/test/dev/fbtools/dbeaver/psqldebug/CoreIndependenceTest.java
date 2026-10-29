package dev.fbtools.dbeaver.psqldebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** O núcleo do Interpretador não pode depender de UI nem de nada do Eclipse/DBeaver (issue 02). */
class CoreIndependenceTest {

    private static final Path SOURCES = Path.of("src/dev/fbtools/dbeaver/psqldebug");
    private static final List<String> CORE_PACKAGES = List.of("psql", "interp", "source");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "^import\\s+(static\\s+)?(org\\.eclipse|org\\.jkiss|org\\.osgi)\\.", Pattern.MULTILINE);

    @Test
    void corePackagesImportNothingFromEclipseOrDbeaver() throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (String corePackage : CORE_PACKAGES) {
            Path dir = SOURCES.resolve(corePackage);
            assertTrue(Files.isDirectory(dir), "pacote do núcleo não encontrado: " + dir.toAbsolutePath());
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    checked++;
                    var matcher = FORBIDDEN.matcher(Files.readString(file));
                    while (matcher.find()) {
                        violations.add(SOURCES.relativize(file) + ": " + matcher.group());
                    }
                }
            }
        }
        assertTrue(checked > 0, "nenhum fonte do núcleo verificado");
        assertEquals(List.of(), violations);
    }
}
