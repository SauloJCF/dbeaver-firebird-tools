package dev.fbtools.dbeaver.psqldebug.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.source.RoutineSourceLoader;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Chamadas sem step into de um texto de Rotina (issue 17), para o aviso "executado no servidor" no editor: só os
 * candidatos que o banco confirma como Rotina, cada um com o statement em que a execução para antes dele.
 */
class ServerCallsTest {

    private static TestDatabase database;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute("CREATE TABLE SC_LOG (V INTEGER)", """
                CREATE OR ALTER FUNCTION SC_F (X INTEGER) RETURNS INTEGER
                AS
                BEGIN
                  RETURN X * 2;
                END""", """
                CREATE OR ALTER PROCEDURE SC_C (X INTEGER)
                RETURNS (Y INTEGER)
                AS
                BEGIN
                  Y = X + 1;
                END""", """
                CREATE OR ALTER PROCEDURE SC_RECUSADA
                RETURNS (N INTEGER)
                AS
                BEGIN
                  IN AUTONOMOUS TRANSACTION DO
                    N = 1;
                END""", """
                CREATE OR ALTER PROCEDURE SC_AVISOS (X INTEGER)
                RETURNS (R INTEGER, T VARCHAR(10))
                AS
                  DECLARE FUNCTION METADE (V INTEGER) RETURNS INTEGER
                  AS
                  BEGIN
                    RETURN SC_F(V) / 4; -- metade
                  END
                BEGIN
                  T = UPPER('a');
                  R = SC_F(X) + METADE(X); -- expressao
                  SELECT COUNT(*) FROM SC_LOG -- dml
                    WHERE V = SC_F(:X) INTO :R; -- dml_chamada
                  EXECUTE PROCEDURE SC_RECUSADA RETURNING_VALUES :R; -- recusada
                  R = METADE(R);
                  R = SC_F(R);
                  EXECUTE PROCEDURE SC_C(1) RETURNING_VALUES :R;
                END""");
    }

    @Test
    void onlyConfirmedRoutinesWithoutStepIntoAreListed() throws Exception {
        try (Connection connection = database.connect()) {
            String text = RoutineSourceLoader.loadProcedure(connection, "SC_AVISOS");
            RoutineDefinition routine = RoutineParser.parse(text);
            List<ServerCalls.Call> calls = ServerCalls.of(routine, new DatabaseRoutineCatalog(connection));

            // Fora da lista: UPPER (função interna), SC_LOG (tabela), e as chamadas elegíveis que o step into alcança
            // (a sub-rotina METADE, SC_F na atribuição inteira e SC_C).
            assertEquals(List.of(
                    "SC_F EXPRESSION dentro de expressão, linha " + line(text, "metade"),
                    "SC_F EXPRESSION dentro de expressão, linha " + line(text, "expressao"),
                    "METADE EXPRESSION dentro de expressão, linha " + line(text, "expressao"),
                    "SC_F DML dentro de Fatia DML, linha " + line(text, "dml_chamada"),
                    "SC_RECUSADA EXECUTE_PROCEDURE Pré-check, linha " + line(text, "recusada")),
                    calls.stream().map(call -> call.site().name() + " " + call.site().form() + " "
                            + (call.reason().startsWith("Pré-check") ? "Pré-check" : call.reason()) + ", linha "
                            + call.site().position().line()).toList());

            // A execução para no statement que contém a chamada: no SELECT de duas linhas, na primeira; na
            // sub-rotina, no RETURN dela.
            assertEquals(List.of(line(text, "metade"), line(text, "expressao"), line(text, "expressao"),
                    line(text, "dml"), line(text, "recusada")),
                    calls.stream().map(ServerCalls.Call::statement).map(SourcePosition::line).toList());

            ServerCalls.Call recusada = calls.get(4);
            assertTrue(recusada.reason().contains("IN AUTONOMOUS TRANSACTION"), recusada.reason());
            assertEquals("SC_F: executado no servidor (dentro de Fatia DML), sem step into", calls.get(3).warning());
        }
    }

    /** Linha que termina com {@code -- tag}. */
    private static int line(String text, String tag) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].stripTrailing().endsWith("-- " + tag)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("sem a tag " + tag);
    }
}
