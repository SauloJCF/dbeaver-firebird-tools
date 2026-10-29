package dev.fbtools.dbeaver.psqldebug.interp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tabela de erros do motor do Firebird 5 ({@code gds-codes.txt}, gerada por {@code tools/gds-codes.sh} a partir
 * dos cabeçalhos do Firebird): GDSCODE, nome simbólico, SQLCODE e SQLSTATE de cada erro.
 */
final class GdsCodes {

    /** {@code isc_except}: exceção de usuário. */
    static final int EXCEPT = 335544517;
    /** {@code isc_random}: argumento texto, que numa exceção de usuário é o nome dela. */
    static final int RANDOM = 335544382;
    /** {@code isc_sqlerr}: "SQL error code = n", que fixa o SQLCODE dos erros de DSQL. */
    static final int SQLERR = 335544436;
    /** SQLCODE de um erro sem SQLCODE próprio ({@code GENERIC_SQLCODE} do motor). */
    static final int GENERIC_SQLCODE = -999;

    private record Entry(int code, int sqlCode, String sqlState) {
    }

    private static final Map<Integer, Entry> BY_CODE = new HashMap<>();
    private static final Map<String, Entry> BY_NAME = new HashMap<>();

    static {
        try (InputStream in = GdsCodes.class.getResourceAsStream("gds-codes.txt")) {
            if (in == null) {
                throw new IllegalStateException("gds-codes.txt não encontrado no classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split(" ");
                Entry entry = new Entry(Integer.parseInt(fields[0]), Integer.parseInt(fields[2]), fields[3]);
                BY_CODE.put(entry.code(), entry);
                BY_NAME.put(fields[1].toLowerCase(Locale.ROOT), entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private GdsCodes() {
    }

    /**
     * @param name nome simbólico, como em {@code WHEN GDSCODE nome}, sem diferença de caixa
     * @throws PsqlExecutionException se o motor não tiver esse erro
     */
    static int code(String name) {
        Entry entry = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        if (entry == null) {
            throw new PsqlExecutionException("GDSCODE desconhecido: " + name);
        }
        return entry.code();
    }

    /** SQLCODE que o motor associa ao erro, ou {@link #GENERIC_SQLCODE}. */
    static int sqlCode(int code) {
        Entry entry = BY_CODE.get(code);
        return entry == null ? GENERIC_SQLCODE : entry.sqlCode();
    }

    /** SQLSTATE que o motor associa ao erro, ou {@code HY000}. */
    static String sqlState(int code) {
        Entry entry = BY_CODE.get(code);
        return entry == null ? "HY000" : entry.sqlState();
    }
}
