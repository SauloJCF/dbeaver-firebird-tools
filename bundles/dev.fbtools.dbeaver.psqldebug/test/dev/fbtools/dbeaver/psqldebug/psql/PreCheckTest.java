package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.UnsupportedConstruct;

/** Pré-check (issue 08): uma Rotina por construção recusada, com a posição de cada uma. */
class PreCheckTest {

    @Test
    void routineWithOnlySupportedConstructsHasNoFindings() {
        assertEquals(List.of(), PreCheck.check("""
                CREATE PROCEDURE P (A INTEGER)
                RETURNS (R VARCHAR(20))
                AS
                DECLARE CUR SCROLL CURSOR FOR (SELECT ID FROM T);
                DECLARE VARIABLE I INTEGER = 0;
                BEGIN
                  L: WHILE (I < A) DO
                  BEGIN
                    I = I + 1;
                    IF (I = 2) THEN CONTINUE L;
                    UPDATE T SET X = :I WHERE CURRENT_DATE > '2020-01-01';
                    IF (ROW_COUNT = 0) THEN LEAVE;
                  END
                  SELECT NOME FROM T WHERE ID = :A INTO :R;
                  INSERT INTO T (ID) VALUES (:A) RETURNING ID INTO :I;
                  MERGE INTO T USING RDB$DATABASE ON T.ID = :A WHEN NOT MATCHED THEN INSERT (ID) VALUES (:A);
                  UPDATE OR INSERT INTO T (ID) VALUES (:A);
                  DELETE FROM T WHERE ID = :A;
                  EXECUTE STATEMENT ('SELECT 1 FROM RDB$DATABASE') WITH COMMON TRANSACTION INTO :I;
                  FOR SELECT ID FROM T WHERE ID > :A INTO :I AS CURSOR C DO
                    INSERT INTO LOG (ID, NOME) VALUES (:C.ID, :I);
                  FOR EXECUTE STATEMENT 'SELECT ID FROM T' INTO :I DO
                    R = R || :I;
                  OPEN CUR;
                  FETCH ABSOLUTE 2 FROM CUR INTO :I;
                  CLOSE CUR;
                  BEGIN
                    EXCEPTION E_ERRO USING (:A, GDSCODE);
                  WHEN EXCEPTION E_ERRO, SQLCODE -803 DO R = SQLSTATE;
                  WHEN ANY DO EXCEPTION;
                  END
                  EXIT;
                END"""));
    }

    @Test
    void whereCurrentOf() {
        assertRefused(UnsupportedConstruct.WHERE_CURRENT_OF, new SourcePosition(5, 7), """
                CREATE PROCEDURE P AS
                BEGIN
                  FOR SELECT ID FROM T FOR UPDATE AS CURSOR C DO
                    UPDATE T SET X = 1
                      WHERE /* posicionado */ CURRENT OF C;
                END""");
    }

    @Test
    void variableOfTheSelectChangedInsideTheForLoop() {
        List<PreCheck.Finding> findings = PreCheck.check("""
                CREATE PROCEDURE P (LIMITE INTEGER) RETURNS (X INTEGER, T INTEGER) AS
                DECLARE Y INTEGER;
                BEGIN
                  FOR SELECT ID FROM T WHERE ID <= :LIMITE AND ID > :X INTO :X DO
                  BEGIN
                    T = X;
                    IF (X > 2) THEN LIMITE = 0;
                    SELECT 1 FROM RDB$DATABASE INTO :T;
                    FOR SELECT ID FROM T INTO :LIMITE DO Y = 1;
                  END
                  LIMITE = 5;
                END""");

        assertEquals(List.of(new SourcePosition(4, 7), new SourcePosition(7, 21), new SourcePosition(9, 5)),
                findings.stream().map(PreCheck.Finding::position).toList());
        assertTrue(findings.stream().allMatch(finding -> finding.description()
                .startsWith(UnsupportedConstruct.CURSOR_VARIABLE_CHANGED.syntax())));
    }

    @Test
    void rowCountInsideTheSelectOfADeclaredCursor() {
        assertRefused(UnsupportedConstruct.ROW_COUNT_IN_DML, new SourcePosition(2, 50), """
                CREATE PROCEDURE P AS
                DECLARE C CURSOR FOR (SELECT ID FROM T WHERE X = ROW_COUNT);
                BEGIN
                  OPEN C;
                END""");
    }

    @Test
    void rdbErrorInsideAHandler() {
        assertRefused(UnsupportedConstruct.RDB_ERROR, new SourcePosition(6, 9), """
                CREATE PROCEDURE P RETURNS (R VARCHAR(100)) AS
                BEGIN
                  BEGIN
                    R = 1 / 0;
                  WHEN ANY DO
                    R = RDB$ERROR(MESSAGE);
                  END
                END""");
    }

    @Test
    void executeStatementOnExternal() {
        assertRefused(UnsupportedConstruct.EXECUTE_STATEMENT_ON_EXTERNAL, new SourcePosition(5, 5), """
                CREATE PROCEDURE P RETURNS (R INTEGER) AS
                BEGIN
                  EXECUTE STATEMENT 'SELECT 1 FROM RDB$DATABASE'
                    WITH COMMON TRANSACTION
                    ON EXTERNAL DATA SOURCE 'localhost:outro.fdb'
                    INTO :R;
                END""");
    }

    @Test
    void executeStatementWithAutonomousTransaction() {
        assertRefused(UnsupportedConstruct.EXECUTE_STATEMENT_AUTONOMOUS, new SourcePosition(3, 39), """
                CREATE PROCEDURE P AS
                BEGIN
                  EXECUTE STATEMENT ('DELETE FROM T') WITH AUTONOMOUS TRANSACTION;
                END""");
    }

    @Test
    void executeStatementAsUser() {
        assertRefused(UnsupportedConstruct.EXECUTE_STATEMENT_AS_USER, new SourcePosition(3, 37), """
                CREATE PROCEDURE P AS
                BEGIN
                  EXECUTE STATEMENT 'DELETE FROM T' AS USER 'OUTRO' PASSWORD 'x' ROLE 'R';
                END""");
    }

    @Test
    void inAutonomousTransaction() {
        assertRefused(UnsupportedConstruct.IN_AUTONOMOUS_TRANSACTION, new SourcePosition(4, 5), """
                CREATE PROCEDURE P AS
                BEGIN
                  IF (1 = 1) THEN
                    IN AUTONOMOUS TRANSACTION DO
                      INSERT INTO LOG (TEXTO) VALUES ('fora da transação');
                END""");
    }

    @Test
    void rowCountInsideDml() {
        assertRefused(UnsupportedConstruct.ROW_COUNT_IN_DML, new SourcePosition(4, 31), """
                CREATE PROCEDURE P AS
                BEGIN
                  UPDATE T SET X = 1;
                  INSERT INTO LOG (N) VALUES (ROW_COUNT);
                END""");
    }

    @Test
    void everyRefusedConstructIsListedInOrder() {
        List<PreCheck.Finding> findings = PreCheck.check("""
                CREATE PROCEDURE P AS
                BEGIN
                  IN AUTONOMOUS TRANSACTION DO
                  BEGIN
                    EXECUTE STATEMENT 'X' ON EXTERNAL 'outro.fdb' AS USER 'U';
                  END
                  DELETE FROM T WHERE CURRENT OF C;
                END""");

        assertEquals(List.of(new SourcePosition(3, 3), new SourcePosition(5, 27), new SourcePosition(5, 51),
                new SourcePosition(7, 17)), findings.stream().map(PreCheck.Finding::position).toList());
    }

    @Test
    void textThatDoesNotParseIsRefusedAtTheErrorPosition() {
        List<PreCheck.Finding> findings = PreCheck.check("""
                CREATE PROCEDURE P AS
                DECLARE X INTEGER;
                BEGIN
                  X = 1;
                  POST_EVENT 'fim';
                END""");

        assertEquals(1, findings.size());
        PreCheck.Finding finding = findings.get(0);
        assertEquals(new SourcePosition(5, 14), finding.position());
        assertTrue(finding.description().contains("'POST_EVENT' (linha 5, coluna 3)"), finding.description());
    }

    private static void assertRefused(UnsupportedConstruct construct, SourcePosition position, String source) {
        assertEquals(List.of(new PreCheck.Finding(position, construct.syntax() + " não é suportado pelo debugger")),
                PreCheck.check(source));
    }
}
