package dev.fbtools.dbeaver.psqldebug.interp;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

/**
 * Testes diferenciais das Fatias DML (issue 04): cada tipo de DML, {@code SELECT … INTO}, {@code RETURNING … INTO},
 * {@code EXECUTE STATEMENT} local, {@code ROW_COUNT} e variáveis de contexto. O estado das tabelas é comparado
 * antes do rollback.
 */
class DmlDifferentialTest {

    private static DifferentialHarness harness;

    @BeforeAll
    static void createRoutines() throws Exception {
        TestDatabase database = TestDatabase.get();
        database.execute("""
                CREATE TABLE DML_ITEM (ID INTEGER NOT NULL PRIMARY KEY, NOME VARCHAR(20), VALOR NUMERIC(18,2))""",
                "CREATE TABLE DML_LOG (TX BIGINT, TEXTO VARCHAR(100), NUMERO INTEGER)",
                "INSERT INTO DML_ITEM VALUES (1, 'um', 10.25)",
                "INSERT INTO DML_ITEM VALUES (2, 'dois', 20.5)",
                "INSERT INTO DML_ITEM VALUES (3, 'três', NULL)", """
                CREATE OR ALTER PROCEDURE DML_INSERT (ID INTEGER, NOME VARCHAR(20), VALOR NUMERIC(18,2))
                RETURNS (RC_VALUES INTEGER, RC_SELECT INTEGER, R_ID INTEGER, R_VALOR NUMERIC(18,2), RC_RETURNING INTEGER)
                AS
                BEGIN
                  INSERT INTO DML_ITEM (ID, NOME, VALOR) VALUES (:ID, :NOME, :VALOR);
                  RC_VALUES = ROW_COUNT;
                  INSERT INTO DML_ITEM (ID, NOME, VALOR)
                    SELECT ID + 100, NOME || '-copia', VALOR * 2 FROM DML_ITEM WHERE ID <= :ID;
                  RC_SELECT = ROW_COUNT;
                  INSERT INTO DML_ITEM (ID, NOME, VALOR) VALUES (:ID + 200, 'retorno', :VALOR / 3)
                    RETURNING ID, VALOR INTO :R_ID, R_VALOR;
                  RC_RETURNING = ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE DML_UPDATE (DELTA NUMERIC(18,2), ALVO INTEGER)
                RETURNS (RC INTEGER, ANTES VARCHAR(20), DEPOIS VARCHAR(20), RC_RETURNING INTEGER, NAO_ACHOU INTEGER)
                AS
                BEGIN
                  UPDATE DML_ITEM SET VALOR = VALOR + :DELTA WHERE ID <> :ALVO;
                  RC = ROW_COUNT;
                  NAO_ACHOU = -1;
                  UPDATE DML_ITEM SET NOME = UPPER(NOME) WHERE ID = :ALVO RETURNING OLD.NOME, NEW.NOME, ID
                    INTO :ANTES, :DEPOIS, :NAO_ACHOU;
                  RC_RETURNING = ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE DML_DELETE (LIMITE INTEGER)
                RETURNS (RC INTEGER, R_NOME VARCHAR(20), RC_RETURNING INTEGER)
                AS
                BEGIN
                  DELETE FROM DML_ITEM WHERE ID > :LIMITE;
                  RC = ROW_COUNT;
                  R_NOME = 'nenhum';
                  DELETE FROM DML_ITEM WHERE ID = :LIMITE RETURNING NOME INTO :R_NOME;
                  RC_RETURNING = ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE DML_MERGE (ID INTEGER, NOME VARCHAR(20))
                RETURNS (RC INTEGER, R_NOME VARCHAR(20), RC_RETURNING INTEGER)
                AS
                BEGIN
                  MERGE INTO DML_ITEM T
                    USING (SELECT :ID AS ID, :NOME AS NOME FROM RDB$DATABASE
                           UNION ALL SELECT 2, 'fixo' FROM RDB$DATABASE) S
                    ON T.ID = S.ID
                    WHEN MATCHED THEN UPDATE SET NOME = S.NOME
                    WHEN NOT MATCHED THEN INSERT (ID, NOME) VALUES (S.ID, S.NOME);
                  RC = ROW_COUNT;
                  MERGE INTO DML_ITEM T USING RDB$DATABASE ON T.ID = :ID + 50
                    WHEN NOT MATCHED THEN INSERT (ID, NOME) VALUES (:ID + 50, :NOME || '+50')
                    RETURNING NOME INTO :R_NOME;
                  RC_RETURNING = ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE DML_UPDATE_OR_INSERT (ID INTEGER, NOME VARCHAR(20))
                RETURNS (RC INTEGER, ANTES VARCHAR(20), RC_RETURNING INTEGER)
                AS
                BEGIN
                  UPDATE OR INSERT INTO DML_ITEM (ID, NOME) VALUES (:ID, :NOME);
                  RC = ROW_COUNT;
                  UPDATE OR INSERT INTO DML_ITEM (ID, NOME) VALUES (:ID, :NOME || '!') MATCHING (ID)
                    RETURNING OLD.NOME INTO :ANTES;
                  RC_RETURNING = ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE DML_SELECT_INTO (ID INTEGER)
                RETURNS (NOME VARCHAR(20), VALOR NUMERIC(18,2), RC INTEGER, ARREDONDADO INTEGER, CURTO CHAR(8),
                         TEXTO VARCHAR(60), PELO_CTE INTEGER, RC_CTE INTEGER)
                AS
                BEGIN
                  NOME = 'antes';
                  VALOR = -1;
                  SELECT NOME, VALOR FROM DML_ITEM WHERE ID = :ID INTO :NOME, :VALOR;
                  RC = ROW_COUNT;
                  SELECT VALOR, VALOR FROM DML_ITEM WHERE ID = :ID INTO ARREDONDADO, :CURTO;
                  SELECT ':ID' || /* :NOME */ ' ' || NOME -- :VALOR
                    FROM DML_ITEM WHERE ID = :ID
                    INTO :TEXTO;
                  WITH MAIORES AS (SELECT ID FROM DML_ITEM WHERE ID >= :ID)
                  SELECT COUNT(*) FROM MAIORES INTO :PELO_CTE;
                  RC_CTE = ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE DML_SINGLETON
                RETURNS (NOME VARCHAR(20))
                AS
                BEGIN
                  INSERT INTO DML_LOG (TEXTO) VALUES ('antes do erro');
                  SELECT NOME FROM DML_ITEM INTO :NOME;
                END""", """
                CREATE OR ALTER PROCEDURE DML_RETURNING_VARIAS
                RETURNS (ID INTEGER)
                AS
                BEGIN
                  INSERT INTO DML_LOG (TEXTO) VALUES ('antes do erro');
                  UPDATE DML_ITEM SET NOME = 'x' RETURNING ID INTO :ID;
                END""", """
                CREATE OR ALTER PROCEDURE DML_CHAVE_DUPLICADA (ID INTEGER)
                AS
                BEGIN
                  INSERT INTO DML_LOG (TEXTO) VALUES ('antes do erro');
                  INSERT INTO DML_ITEM (ID, NOME) VALUES (:ID, 'duplicado');
                END""", """
                CREATE OR ALTER PROCEDURE DML_EXECUTE_STATEMENT (ID INTEGER)
                RETURNS (POSICIONAL VARCHAR(20), NOMEADO VARCHAR(20), SEM_LINHA VARCHAR(20), RC INTEGER,
                         CONTAGEM INTEGER, PELA_VARIAVEL INTEGER)
                AS
                DECLARE VARIABLE TEXTO_SQL VARCHAR(200) = 'SELECT COUNT(*) FROM DML_ITEM';
                BEGIN
                  UPDATE DML_ITEM SET VALOR = VALOR WHERE ID <= 2;
                  EXECUTE STATEMENT 'INSERT INTO DML_LOG (TEXTO, NUMERO) VALUES (''dinâmico'', 1)';
                  EXECUTE STATEMENT ('SELECT NOME FROM DML_ITEM WHERE ID = ?') (:ID) INTO :POSICIONAL;
                  EXECUTE STATEMENT ('SELECT NOME FROM DML_ITEM WHERE ID = :X AND :X > 0 AND '':X'' <> '''' ')
                    (X := ID, EXCESS NAO_USADO := 1) WITH COMMON TRANSACTION INTO NOMEADO;
                  SEM_LINHA = 'mantido';
                  EXECUTE STATEMENT ('SELECT NOME FROM DML_ITEM WHERE ID = ?') (-1) INTO :SEM_LINHA;
                  RC = ROW_COUNT;
                  EXECUTE STATEMENT ('INSERT INTO DML_ITEM (ID, NOME) VALUES (?, ?)') (:ID + 300, 'via ES');
                  EXECUTE STATEMENT 'SELECT COUNT(*) FROM DML_LOG' INTO :CONTAGEM;
                  EXECUTE STATEMENT TEXTO_SQL INTO :PELA_VARIAVEL;
                END""", """
                CREATE OR ALTER PROCEDURE DML_EXECUTE_STATEMENT_VARIAS
                RETURNS (NOME VARCHAR(20))
                AS
                BEGIN
                  EXECUTE STATEMENT 'SELECT NOME FROM DML_ITEM' INTO :NOME;
                END""", """
                CREATE OR ALTER PROCEDURE DML_ROW_COUNT
                RETURNS (R1 INTEGER, R2 INTEGER, R3 INTEGER, R4 INTEGER, R5 INTEGER, R6 INTEGER)
                AS
                DECLARE VARIABLE V VARCHAR(20);
                BEGIN
                  R1 = ROW_COUNT;
                  UPDATE DML_ITEM SET NOME = NOME;
                  R2 = ROW_COUNT;
                  V = 'atribuição não muda';
                  IF (ROW_COUNT = 3) THEN R3 = ROW_COUNT;
                  INSERT INTO DML_LOG (TEXTO, NUMERO) VALUES ('linhas', :R2);
                  R4 = ROW_COUNT;
                  SELECT NUMERO FROM DML_LOG WHERE TEXTO = 'linhas' INTO :R5;
                  DELETE FROM DML_ITEM WHERE ID = -1;
                  R6 = ROW_COUNT;
                END""", """
                CREATE OR ALTER PROCEDURE DML_CONTEXTO
                RETURNS (USUARIO VARCHAR(63), MESMA_TRANSACAO INTEGER, CONTEXTO VARCHAR(20), DUMMY VARCHAR(10),
                         ATUALIZADAS INTEGER)
                AS
                BEGIN
                  INSERT INTO DML_LOG (TX, TEXTO) VALUES (CURRENT_TRANSACTION, CURRENT_USER);
                  SELECT COUNT(*) FROM DML_LOG WHERE TX = CURRENT_TRANSACTION INTO :MESMA_TRANSACAO;
                  SELECT RDB$SET_CONTEXT('USER_TRANSACTION', 'DML_K', 'valor ' || :MESMA_TRANSACAO)
                    FROM RDB$DATABASE INTO :DUMMY;
                  CONTEXTO = RDB$GET_CONTEXT('USER_TRANSACTION', 'DML_K');
                  SELECT TEXTO FROM DML_LOG WHERE TX = CURRENT_TRANSACTION INTO :USUARIO;
                  -- CURRENT_TRANSACTION muda a cada execução: zerado para a comparação das tabelas.
                  UPDATE DML_LOG SET TX = 0 WHERE TX = CURRENT_TRANSACTION;
                  ATUALIZADAS = ROW_COUNT;
                END""");
        harness = new DifferentialHarness(database).tables("DML_ITEM", "DML_LOG");
    }

    @Test
    void insertWithValuesSelectAndReturning() throws Exception {
        harness.assertSameOutputs("DML_INSERT", 10, "dez", new BigDecimal("7.75"));
        harness.assertSameOutputs("DML_INSERT", 20, null, null);
    }

    @Test
    void updateWithReturningOldAndNew() throws Exception {
        harness.assertSameOutputs("DML_UPDATE", new BigDecimal("1.5"), 2);
        harness.assertSameOutputs("DML_UPDATE", new BigDecimal("1.5"), 99);
    }

    @Test
    void deleteWithReturning() throws Exception {
        harness.assertSameOutputs("DML_DELETE", 2);
        harness.assertSameOutputs("DML_DELETE", 99);
    }

    @Test
    void mergeWithAndWithoutReturning() throws Exception {
        harness.assertSameOutputs("DML_MERGE", 1, "novo um");
        harness.assertSameOutputs("DML_MERGE", 7, "sete");
    }

    @Test
    void updateOrInsertWithReturning() throws Exception {
        harness.assertSameOutputs("DML_UPDATE_OR_INSERT", 1, "outro");
        harness.assertSameOutputs("DML_UPDATE_OR_INSERT", 8, "oito");
    }

    @Test
    void selectIntoConvertsLikeAnAssignmentAndKeepsTargetsWithoutRow() throws Exception {
        harness.assertSameOutputs("DML_SELECT_INTO", 1);
        harness.assertSameOutputs("DML_SELECT_INTO", 2);
        harness.assertSameOutputs("DML_SELECT_INTO", 3);
        harness.assertSameOutputs("DML_SELECT_INTO", 99);
    }

    @Test
    void moreThanOneRowRaisesTheSingletonErrorAndUndoesTheCall() throws Exception {
        harness.assertSameOutputs("DML_SINGLETON");
        harness.assertSameOutputs("DML_RETURNING_VARIAS");
        harness.assertSameOutputs("DML_EXECUTE_STATEMENT_VARIAS");
    }

    @Test
    void serverErrorInDmlEndsTheRoutineWithTheSameError() throws Exception {
        harness.assertSameOutputs("DML_CHAVE_DUPLICADA", 1);
        harness.assertSameOutputs("DML_CHAVE_DUPLICADA", 50);
    }

    @Test
    void localExecuteStatementWithIntoAndParameters() throws Exception {
        harness.assertSameOutputs("DML_EXECUTE_STATEMENT", 1);
        harness.assertSameOutputs("DML_EXECUTE_STATEMENT", 3);
    }

    @Test
    void rowCountFollowsEachDml() throws Exception {
        harness.assertSameOutputs("DML_ROW_COUNT");
    }

    @Test
    void contextVariablesAreResolvedByTheServerInTheSameTransaction() throws Exception {
        harness.assertSameOutputs("DML_CONTEXTO");
    }
}
