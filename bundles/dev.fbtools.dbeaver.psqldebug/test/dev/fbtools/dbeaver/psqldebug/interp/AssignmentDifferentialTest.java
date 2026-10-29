package dev.fbtools.dbeaver.psqldebug.interp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/** Testes diferenciais do tracer bullet: parâmetros, {@code DECLARE}, atribuições e blocos (issue 02). */
class AssignmentDifferentialTest {

    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        TestDatabase database = TestDatabase.get();
        database.execute("""
                CREATE OR ALTER PROCEDURE DT_NUMERIC (A NUMERIC(18,4), B NUMERIC(18,4))
                RETURNS (SOMA NUMERIC(18,4), DIFERENCA NUMERIC(18,4), PRODUTO NUMERIC(18,4),
                         QUOCIENTE NUMERIC(18,4), MEDIA NUMERIC(18,4), PERCENTUAL NUMERIC(18,2))
                AS
                DECLARE VARIABLE TOTAL NUMERIC(18,4);
                BEGIN
                  SOMA = A + B;
                  DIFERENCA = A - B;
                  PRODUTO = A * B;
                  QUOCIENTE = A / B;
                  TOTAL = SOMA * 2;
                  MEDIA = TOTAL / 3;
                  PERCENTUAL = A * 100 / NULLIF(B, 0);
                END""", """
                CREATE OR ALTER PROCEDURE DT_NULL (A INTEGER, S VARCHAR(20))
                RETURNS (SOMA INTEGER, CONCATENADO VARCHAR(40), COALESCIDO INTEGER, NULO_EXPLICITO VARCHAR(10),
                         EH_NULO BOOLEAN, IGUAL BOOLEAN, NUNCA_ATRIBUIDO INTEGER)
                AS
                BEGIN
                  SOMA = A + NULL;
                  CONCATENADO = S || '-' || A;
                  COALESCIDO = COALESCE(A, -1);
                  NULO_EXPLICITO = NULL;
                  EH_NULO = A IS NULL;
                  IGUAL = (A = 1);
                END""", """
                CREATE OR ALTER PROCEDURE DT_SUBSTRING (S VARCHAR(30), INICIO INTEGER, TAMANHO INTEGER)
                RETURNS (PEDACO VARCHAR(30), DESDE VARCHAR(30), PREFIXO CHAR(5), TAM INTEGER,
                         MAIUSCULO VARCHAR(30), APARADO VARCHAR(30), ROTULO VARCHAR(40))
                AS
                BEGIN
                  PEDACO = SUBSTRING(S FROM INICIO FOR TAMANHO);
                  DESDE = SUBSTRING(S FROM INICIO);
                  PREFIXO = SUBSTRING(S FROM 1 FOR 3);
                  TAM = CHAR_LENGTH(S);
                  MAIUSCULO = UPPER(S);
                  APARADO = TRIM(BOTH ' ' FROM S);
                  ROTULO = 'ação: ?' || TRIM(S);
                END""", """
                CREATE OR ALTER PROCEDURE DT_CAST (TEXTO VARCHAR(20), VALOR NUMERIC(18,4))
                RETURNS (COMO_DATA DATE, COMO_INTEIRO INTEGER, COMO_TEXTO VARCHAR(30), ARREDONDADO INTEGER,
                         COMO_TIMESTAMP TIMESTAMP, TEXTO_PARA_NUMERO NUMERIC(9,2), MENOS_ESCALA NUMERIC(18,1))
                AS
                BEGIN
                  COMO_DATA = CAST(TEXTO AS DATE);
                  COMO_INTEIRO = CAST(VALOR AS INTEGER);
                  COMO_TEXTO = CAST(VALOR AS VARCHAR(30));
                  ARREDONDADO = VALOR;
                  COMO_TIMESTAMP = CAST(TEXTO || ' 13:45:10.1234' AS TIMESTAMP);
                  TEXTO_PARA_NUMERO = '12.345';
                  MENOS_ESCALA = VALOR;
                END""", """
                CREATE OR ALTER PROCEDURE DT_DATEADD (D DATE, T TIMESTAMP, N INTEGER)
                RETURNS (MAIS_DIAS DATE, MAIS_MESES DATE, MAIS_HORAS TIMESTAMP, DIFERENCA_DIAS BIGINT,
                         FIM_DO_MES DATE, DIA_DA_SEMANA SMALLINT)
                AS
                BEGIN
                  MAIS_DIAS = DATEADD(DAY, N, D);
                  MAIS_MESES = DATEADD(N MONTH TO D);
                  MAIS_HORAS = DATEADD(HOUR, N, T);
                  DIFERENCA_DIAS = DATEDIFF(DAY, D, MAIS_MESES);
                  FIM_DO_MES = DATEADD(-1 DAY TO DATEADD(1 MONTH TO D - EXTRACT(DAY FROM D) + 1));
                  DIA_DA_SEMANA = EXTRACT(WEEKDAY FROM D);
                END""", """
                CREATE OR ALTER PROCEDURE DT_DECLARE (BASE INTEGER, FATOR NUMERIC(9,3) = 1.5)
                RETURNS (R1 INTEGER, R2 NUMERIC(18,4), R3 VARCHAR(60), R4 INTEGER)
                AS
                DECLARE VARIABLE CONTADOR INTEGER = 0;
                DECLARE VARIABLE DOBRO INTEGER DEFAULT 2;
                DECLARE ESCALADO NUMERIC(18,4) = 0;
                DECLARE VARIABLE SEM_VALOR VARCHAR(10);
                DECLARE VARIABLE "Misto" INTEGER = 7;
                DECLARE VARIABLE INICIO DATE = CURRENT_DATE;
                DECLARE VARIABLE OBRIGATORIO INTEGER NOT NULL = 1;
                DECLARE VARIABLE TEXTO VARCHAR(50) CHARACTER SET UTF8 = 'base=';
                BEGIN
                  CONTADOR = CONTADOR + 1;
                  BEGIN
                    CONTADOR = CONTADOR + DOBRO;
                    BEGIN
                      ESCALADO = BASE * FATOR / 7;
                    END
                  END
                  R1 = CONTADOR + BASE * 10 + "Misto" + OBRIGATORIO + DATEDIFF(DAY, INICIO, CURRENT_DATE);
                  R2 = ESCALADO;
                  R3 = TEXTO || BASE || ' fator=' || FATOR || COALESCE(SEM_VALOR, ' ?');
                  R4 = BASE -- comentário no meio da expressão
                       + 1;
                END""");
        harness = new DifferentialHarness(database);
    }

    @Test
    void numericArithmeticWithScale() throws Exception {
        harness.assertSameOutputs("DT_NUMERIC", new BigDecimal("10.1234"), new BigDecimal("3.3333"));
        harness.assertSameOutputs("DT_NUMERIC", new BigDecimal("-7.5"), new BigDecimal("2"));
        harness.assertSameOutputs("DT_NUMERIC", new BigDecimal("0.0001"), new BigDecimal("3"));
        harness.assertSameOutputs("DT_NUMERIC", new BigDecimal("1"), null);
    }

    @Test
    void nullPropagation() throws Exception {
        harness.assertSameOutputs("DT_NULL", null, null);
        harness.assertSameOutputs("DT_NULL", 5, "x");
        harness.assertSameOutputs("DT_NULL", 1, null);
    }

    @Test
    void substringAndStrings() throws Exception {
        harness.assertSameOutputs("DT_SUBSTRING", "  Firebird PSQL  ", 3, 5);
        harness.assertSameOutputs("DT_SUBSTRING", "ação", 2, 10);
        harness.assertSameOutputs("DT_SUBSTRING", null, 1, 1);
    }

    @Test
    void explicitAndImplicitCasts() throws Exception {
        harness.assertSameOutputs("DT_CAST", "2026-10-14", new BigDecimal("2.5"));
        harness.assertSameOutputs("DT_CAST", "14.10.2026", new BigDecimal("-2.5"));
        harness.assertSameOutputs("DT_CAST", "2024-02-29", new BigDecimal("1234.5678"));
    }

    @Test
    void dateArithmetic() throws Exception {
        harness.assertSameOutputs("DT_DATEADD", LocalDate.of(2024, 1, 31), LocalDateTime.of(2024, 1, 31, 23, 30), 1);
        harness.assertSameOutputs("DT_DATEADD", LocalDate.of(2026, 10, 14), LocalDateTime.of(2026, 10, 14, 8, 0), -13);
        harness.assertSameOutputs("DT_DATEADD", LocalDate.of(2025, 2, 28), null, null);
    }

    @Test
    void declarationsDefaultsAndNestedBlocks() throws Exception {
        harness.assertSameOutputs("DT_DECLARE", 3);
        harness.assertSameOutputs("DT_DECLARE", 3, new BigDecimal("2.25"));
        harness.assertSameOutputs("DT_DECLARE", null, null);
    }
}
