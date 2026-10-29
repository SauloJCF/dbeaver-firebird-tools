package dev.fbtools.dbeaver.psqldebug.interp;

import java.math.BigDecimal;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais de function, rotina de package e {@code EXECUTE BLOCK} como Rotina raiz (issue 09): function
 * chamada por {@code SELECT f(...) FROM RDB$DATABASE}, procedure de package por {@code EXECUTE PROCEDURE pkg.sp},
 * {@code EXECUTE BLOCK} executado direto.
 */
class RootRoutineDifferentialTest {

    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        TestDatabase database = TestDatabase.get();
        database.execute(
                "CREATE TABLE RR_ITEM (ID INTEGER NOT NULL PRIMARY KEY, NOME VARCHAR(20), VALOR NUMERIC(18,2))",
                "CREATE TABLE RR_LOG (TEXTO VARCHAR(100))",
                "INSERT INTO RR_ITEM VALUES (1, 'um', 10.25)",
                "INSERT INTO RR_ITEM VALUES (2, 'dois', NULL)",
                "INSERT INTO RR_ITEM VALUES (3, 'três', 30.5)", """
                CREATE OR ALTER FUNCTION RR_CALCULA (A INTEGER, FATOR NUMERIC(9,3) = 1.5, NOME TYPE OF COLUMN RR_ITEM.NOME = 'x')
                RETURNS NUMERIC(18,2) DETERMINISTIC
                AS
                DECLARE V NUMERIC(18,4);
                BEGIN
                  V = A * FATOR;
                  IF (NOME = 'nulo') THEN RETURN NULL;
                  IF (A < 0) THEN EXIT; -- sem RETURN: NULL
                  RETURN V / 3; -- convertido para NUMERIC(18,2), com arredondamento
                END""", """
                CREATE OR ALTER FUNCTION RR_TEXTO (K INTEGER)
                RETURNS VARCHAR(5)
                AS
                BEGIN
                  IF (K = 1) THEN RETURN 'abcdefgh'; -- truncamento
                  IF (K = 2) THEN RETURN 12.5;
                  RETURN K;
                END""", """
                CREATE OR ALTER FUNCTION RR_LACO (LIMITE INTEGER)
                RETURNS VARCHAR(100)
                AS
                DECLARE ID INTEGER;
                DECLARE TRILHA VARCHAR(100) = '';
                BEGIN
                  FOR SELECT ID FROM RR_ITEM ORDER BY ID INTO :ID DO
                  BEGIN
                    INSERT INTO RR_LOG VALUES ('função ' || :ID);
                    TRILHA = TRILHA || ID || ';';
                    IF (ID = LIMITE) THEN RETURN TRILHA; -- RETURN dentro do laço fecha o cursor
                  END
                  IF (LIMITE = 0) THEN TRILHA = TRILHA || (1 / LIMITE);
                  RETURN TRILHA || 'fim';
                END""", """
                CREATE OR ALTER FUNCTION RR_NAO_NULO (K INTEGER)
                RETURNS INTEGER NOT NULL
                AS
                BEGIN
                  IF (K = 1) THEN EXIT; -- o erro do fim não é tratável
                  BEGIN
                    RETURN NULL;
                    WHEN ANY DO RETURN GDSCODE; -- o do RETURN é
                  END
                END""", """
                CREATE OR ALTER FUNCTION RR_SEM_ENTRADA
                RETURNS DATE NOT NULL SQL SECURITY INVOKER
                AS
                BEGIN
                  RETURN DATEADD(DAY, 1, DATE '2026-01-31');
                END""", """
                CREATE OR ALTER PACKAGE RR_PKG
                AS
                BEGIN
                  PROCEDURE SOMA (A INTEGER, B INTEGER = 10) RETURNS (TOTAL INTEGER, TEXTO VARCHAR(30));
                  PROCEDURE LISTA (MINIMO INTEGER) RETURNS (ID INTEGER, NOME VARCHAR(20));
                  FUNCTION DOBRO (X NUMERIC(9,2) = 2) RETURNS NUMERIC(18,2);
                END""", """
                RECREATE PACKAGE BODY RR_PKG
                AS
                BEGIN
                  PROCEDURE SOMA (A INTEGER, B INTEGER) RETURNS (TOTAL INTEGER, TEXTO VARCHAR(30))
                  AS
                  DECLARE I INTEGER = 0;
                  BEGIN
                    TOTAL = 0;
                    WHILE (I < B) DO
                    BEGIN
                      I = I + 1;
                      TOTAL = TOTAL + A;
                    END
                    INSERT INTO RR_LOG VALUES ('soma ' || :TOTAL);
                    TEXTO = 'soma de ' || B;
                  END

                  PROCEDURE LISTA (MINIMO INTEGER) RETURNS (ID INTEGER, NOME VARCHAR(20))
                  AS
                  BEGIN
                    FOR SELECT ID, NOME FROM RR_ITEM WHERE ID >= :MINIMO ORDER BY ID INTO :ID, :NOME DO
                      SUSPEND;
                  END

                  FUNCTION DOBRO (X NUMERIC(9,2)) RETURNS NUMERIC(18,2)
                  AS
                  BEGIN
                    RETURN X * 2;
                  END

                  PROCEDURE PRIVADA AS
                  BEGIN
                  END
                END""");
        harness = new DifferentialHarness(database).tables("RR_ITEM", "RR_LOG");
    }

    @Test
    void functionReturnsTheValueConvertedToTheReturnType() throws SQLException {
        harness.assertSameResult("RR_CALCULA", 7);
        harness.assertSameResult("RR_CALCULA", 7, new BigDecimal("0.333"));
        harness.assertSameResult("RR_CALCULA", -1, 2, "a");
        harness.assertSameResult("RR_CALCULA", 1, 2, "nulo");
        harness.assertSameResult("RR_CALCULA", (Object) null);
        for (int k = 0; k <= 2; k++) {
            harness.assertSameResult("RR_TEXTO", k);
        }
        harness.assertSameResult("RR_SEM_ENTRADA");
    }

    @Test
    void functionWithDmlAndReturnInsideALoop() throws SQLException {
        for (int limite = 0; limite <= 4; limite++) {
            harness.assertSameResult("RR_LACO", limite); // 0: erro, que desfaz o DML da function
        }
    }

    @Test
    void nullInNotNullReturn() throws SQLException {
        harness.assertSameResult("RR_NAO_NULO", 0);
        harness.assertSameResult("RR_NAO_NULO", 1);
    }

    @Test
    void packageProcedureWithTheDefaultsOfThePackageHeader() throws SQLException {
        harness.assertSamePackageOutputs("RR_PKG", "SOMA", 3, 4);
        harness.assertSamePackageOutputs("RR_PKG", "SOMA", 3);
        harness.assertSamePackageRows("RR_PKG", "LISTA", 2);
    }

    @Test
    void packageFunction() throws SQLException {
        harness.assertSamePackageResult("RR_PKG", "DOBRO", new BigDecimal("1.25"));
        harness.assertSamePackageResult("RR_PKG", "DOBRO");
    }

    @Test
    void executeBlockWithInputsAndOutputs() throws SQLException {
        harness.assertSameExecuteBlock("""
                EXECUTE BLOCK (A INTEGER = ?, NOME VARCHAR(10) = ?)
                RETURNS (TOTAL NUMERIC(18,2), TEXTO VARCHAR(30))
                AS
                DECLARE V NUMERIC(18,2);
                BEGIN
                  SELECT SUM(VALOR) FROM RR_ITEM WHERE ID <= :A INTO :V;
                  TOTAL = V * 2;
                  INSERT INTO RR_LOG VALUES (:NOME || ' ' || :TOTAL);
                  TEXTO = NOME || '!';
                END""", 2, "bloco");
        harness.assertSameExecuteBlock("""
                EXECUTE BLOCK
                AS
                BEGIN
                  UPDATE RR_ITEM SET VALOR = COALESCE(VALOR, 0) + 1;
                END""");
    }

    @Test
    void executeBlockWithSuspend() throws SQLException {
        harness.assertSameExecuteBlock("""
                EXECUTE BLOCK (MINIMO INTEGER = ?)
                RETURNS (ID INTEGER, NOME VARCHAR(20), N INTEGER)
                AS
                BEGIN
                  N = 0;
                  FOR SELECT ID, NOME FROM RR_ITEM WHERE ID >= :MINIMO ORDER BY ID INTO :ID, :NOME DO
                  BEGIN
                    N = N + 1;
                    INSERT INTO RR_LOG VALUES (:NOME);
                    SUSPEND;
                  END
                END""", 2);
    }
}
