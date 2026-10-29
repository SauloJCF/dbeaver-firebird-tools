package dev.fbtools.dbeaver.psqldebug.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.RoutineParser;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Assignment;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;
import dev.fbtools.dbeaver.psqldebug.testing.DifferentialHarness;
import dev.fbtools.dbeaver.psqldebug.testing.TestDatabase;

class RoutineSourceLoaderTest {

    private static TestDatabase database;

    @BeforeAll
    static void createRoutines() throws Exception {
        database = TestDatabase.get();
        database.execute(
                "CREATE DOMAIN DM_VALOR AS NUMERIC(15,2)",
                "CREATE TABLE DT_TABELA (NOME VARCHAR(15) CHARACTER SET WIN1252, QTD SMALLINT)",
                """
                CREATE OR ALTER PROCEDURE DT_TIPOS (V DM_VALOR, TV TYPE OF DM_VALOR,
                    NOME TYPE OF COLUMN DT_TABELA.NOME, FLAG BOOLEAN, GRANDE BIGINT, D DOUBLE PRECISION,
                    TXT BLOB SUB_TYPE TEXT, C CHAR(3) NOT NULL, Q SMALLINT = 4)
                RETURNS (SOMA DM_VALOR, NOME_LONGO VARCHAR(40), NEGADO BOOLEAN, METADE DOUBLE PRECISION,
                    GRANDE_MAIS BIGINT, TXT_MAIS VARCHAR(100), C_MAIS VARCHAR(10), TERCO DECIMAL(9,3))
                AS
                BEGIN
                  SOMA = V + TV;
                  NOME_LONGO = NOME || '!';
                  NEGADO = NOT FLAG;
                  METADE = D / 2;
                  GRANDE_MAIS = GRANDE + Q;
                  TXT_MAIS = TXT || ' fim';
                  C_MAIS = C || '|';
                  TERCO = V / 3;
                END""",
                "CREATE OR ALTER PROCEDURE DT_SO_SAIDAS RETURNS (A INTEGER, B INTEGER) AS BEGIN A = 1; END",
                "CREATE OR ALTER PROCEDURE DT_SO_ENTRADAS (A INTEGER, B INTEGER = 2) AS BEGIN A = B; END",
                "CREATE OR ALTER PROCEDURE DT_SEM_PARAMETROS AS BEGIN EXIT; END",
                """
                CREATE OR ALTER FUNCTION DT_FUNCAO (A INTEGER, B DM_VALOR = 1)
                RETURNS VARCHAR(10) NOT NULL DETERMINISTIC
                AS
                DECLARE X INTEGER;
                BEGIN
                  X = A;
                  RETURN X || B;
                END""",
                "CREATE OR ALTER FUNCTION DT_FUNCAO_SEM_ENTRADAS RETURNS TYPE OF DM_VALOR AS BEGIN RETURN 1; END",
                "CREATE OR ALTER FUNCTION DT_FUNCAO_DEFINER (A INTEGER) RETURNS INTEGER SQL SECURITY DEFINER AS "
                        + "BEGIN RETURN A; END",
                "CREATE OR ALTER PACKAGE DT_PKG AS BEGIN PROCEDURE P (X INTEGER = 5); END",
                """
                RECREATE PACKAGE BODY DT_PKG AS
                BEGIN
                  PROCEDURE P (X INTEGER)
                  AS
                  BEGIN
                    X = X + 1;
                  END
                END""",
                "CREATE OR ALTER PACKAGE DT_PKG_SEM_CORPO AS BEGIN PROCEDURE P; END");
    }

    @Test
    void packagesAreThoseWithABody() throws Exception {
        List<String> packages;
        try (Connection connection = database.connect()) {
            packages = RoutineSourceLoader.listPackages(connection);
        }

        // Outras classes de teste criam packages no mesmo banco: só as daqui são conferidas.
        assertTrue(packages.contains("DT_PKG"), packages.toString());
        assertTrue(!packages.contains("DT_PKG_SEM_CORPO"), packages.toString());
        assertEquals(packages.stream().sorted().toList(), packages);
    }

    /**
     * O cabeçalho tem as quebras de linha do texto que o editor do DBeaver 26.2.2 mostra
     * ({@code FireBirdUtils.getProcedureSourceWithHeader}): entradas na linha do {@code CREATE}, {@code RETURNS (},
     * uma saída por linha (com tabulação), {@code )}, {@code AS}, e o corpo gravado no banco. Assim o corpo começa na
     * mesma linha do editor, e as posições do AST no corpo são as do editor.
     */
    @Test
    void headerFollowsTheLineLayoutOfTheDbeaverEditor() throws Exception {
        try (Connection connection = database.connect()) {
            assertEquals("""
                    CREATE OR ALTER PROCEDURE "DT_SO_SAIDAS" RETURNS (
                    \t"A" INTEGER,
                    \t"B" INTEGER
                    )
                    AS
                    BEGIN A = 1; END""", RoutineSourceLoader.loadProcedure(connection, "DT_SO_SAIDAS"));
            assertEquals("""
                    CREATE OR ALTER PROCEDURE "DT_SO_ENTRADAS" ("A" INTEGER, "B" INTEGER = 2)
                    AS
                    BEGIN A = B; END""", RoutineSourceLoader.loadProcedure(connection, "DT_SO_ENTRADAS"));
            assertEquals("""
                    CREATE OR ALTER PROCEDURE "DT_SEM_PARAMETROS" AS
                    BEGIN EXIT; END""", RoutineSourceLoader.loadProcedure(connection, "DT_SEM_PARAMETROS"));
            assertEquals("""
                    CREATE OR ALTER FUNCTION "DT_FUNCAO" ("A" INTEGER, "B" "DM_VALOR" = 1)
                    RETURNS VARCHAR(10) CHARACTER SET UTF8 NOT NULL
                    AS
                    DECLARE X INTEGER;
                    BEGIN
                      X = A;
                      RETURN X || B;
                    END""", RoutineSourceLoader.loadFunction(connection, "DT_FUNCAO"));
            assertEquals("""
                    CREATE OR ALTER FUNCTION "DT_FUNCAO_SEM_ENTRADAS" RETURNS TYPE OF "DM_VALOR"
                    AS
                    BEGIN RETURN 1; END""", RoutineSourceLoader.loadFunction(connection, "DT_FUNCAO_SEM_ENTRADAS"));
            // SQL SECURITY explícito: o DBeaver o põe numa linha própria, antes do AS.
            String definer = RoutineSourceLoader.loadFunction(connection, "DT_FUNCAO_DEFINER");
            assertEquals("""
                    CREATE OR ALTER FUNCTION "DT_FUNCAO_DEFINER" ("A" INTEGER)
                    RETURNS INTEGER
                    SQL SECURITY DEFINER
                    AS
                    BEGIN RETURN A; END""", definer);
            RoutineDefinition parsed = RoutineParser.parse(definer);
            assertEquals("INTEGER", parsed.returnValue().type());
            assertEquals(new SourcePosition(5, 7), parsed.body().statements().get(0).position());
        }
    }

    @Test
    void astPositionsAreThoseOfTheLoadedText() throws Exception {
        String source;
        RoutineSourceLoader.PackageSource pkg;
        try (Connection connection = database.connect()) {
            source = RoutineSourceLoader.loadProcedure(connection, "DT_TIPOS");
            pkg = RoutineSourceLoader.loadPackage(connection, "DT_PKG");
        }
        List<String> lines = source.lines().toList();
        RoutineDefinition procedure = RoutineParser.parse(source);
        // CREATE ... (entradas), RETURNS (, 8 saídas, ), AS: o BEGIN é a linha 13 e o primeiro statement, a 14.
        assertEquals(new SourcePosition(13, 1), procedure.body().position());
        for (Statement statement : procedure.body().statements()) {
            SourcePosition position = statement.position();
            String target = ((Assignment) statement).target();
            assertTrue(lines.get(position.line() - 1).substring(position.column() - 1).startsWith(target + " ="),
                    statement + " em " + position);
        }
        assertEquals(new SourcePosition(14, 3), procedure.body().statements().get(0).position());

        RoutineDefinition packaged = RoutineParser.parsePackageRoutine(pkg.header(), pkg.body(),
                RoutineDefinition.Kind.PROCEDURE, "P");
        assertTrue(pkg.body().startsWith("RECREATE PACKAGE BODY \"DT_PKG\"\nAS\nBEGIN\n"), pkg.body());
        assertEquals(new SourcePosition(7, 5), packaged.body().statements().get(0).position());
        assertEquals("5", packaged.inputs().get(0).defaultValue().text());
    }

    @Test
    void headerIsRebuiltFromMetadata() throws Exception {
        String source;
        try (Connection connection = database.connect()) {
            source = RoutineSourceLoader.loadProcedure(connection, "DT_TIPOS");
        }
        RoutineDefinition procedure = RoutineParser.parse(source);

        assertEquals(List.of(
                "\"DM_VALOR\"",
                "TYPE OF \"DM_VALOR\"",
                "TYPE OF COLUMN \"DT_TABELA\".\"NOME\"",
                "BOOLEAN",
                "BIGINT",
                "DOUBLE PRECISION",
                "BLOB SUB_TYPE 1 CHARACTER SET UTF8",
                "CHAR(3) CHARACTER SET UTF8",
                "SMALLINT"), procedure.inputs().stream().map(ParameterDeclaration::type).toList());
        assertTrue(procedure.inputs().get(7).notNull());
        assertEquals("4", procedure.inputs().get(8).defaultValue().text());
        assertEquals("DECIMAL(9,3)", procedure.outputs().get(7).type());
        assertTrue(source.contains("\nAS\nBEGIN\n  SOMA = V + TV;\n"), source);
    }

    @Test
    void domainsAndTypeOfBehaveLikeTheServer() throws Exception {
        new DifferentialHarness(database).assertSameOutputs("DT_TIPOS", new BigDecimal("10.555"),
                new BigDecimal("0.01"), "ação", true, 9_000_000_000L, 5.5, "texto", "ab");
    }
}
