package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Linhas onde o debugger pode parar, que recebem os breakpoints (issue 14). */
class StatementLinesTest {

    @Test
    void everyStatementButBlocksIncludingHandlersBranchesLoopsAndSubroutines() {
        String text = """
                CREATE OR ALTER PROCEDURE P (N INTEGER)
                RETURNS (R INTEGER)
                AS
                  DECLARE I INTEGER = 0;
                  DECLARE FUNCTION DOBRO (X INTEGER) RETURNS INTEGER
                  AS
                  BEGIN
                    RETURN X * 2;
                  END
                BEGIN
                  R = 0;
                  WHILE (I < N) DO
                  BEGIN
                    I = I + 1;
                    IF (I > 2) THEN
                      R = R + 1;
                    ELSE
                    BEGIN
                      R = R - 1;
                    END
                  END
                  BEGIN
                    R = 1 / R;
                  WHEN ANY DO
                    R = -1;
                  END
                  FOR SELECT 1 FROM RDB$DATABASE INTO :I DO R = DOBRO(R);
                END""";

        assertEquals(List.of(8, 11, 12, 14, 15, 16, 19, 23, 25, 27),
                List.copyOf(StatementLines.of(RoutineParser.parse(text))));
    }
}
