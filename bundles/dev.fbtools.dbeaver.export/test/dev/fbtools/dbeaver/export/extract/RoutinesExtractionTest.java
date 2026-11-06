package dev.fbtools.dbeaver.export.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.export.testing.AllowedDiffs;
import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;
import dev.fbtools.dbeaver.export.testing.ScriptComparator;

/**
 * Issue 05: procedures, functions e packages (Cabeçalhos vazios e corpos). O banco sintético contra a Referência do
 * IBExpert e ida e volta com o que só o Firebird 5 tem. Exige o Firebird 5 local (ADR 0008).
 */
class RoutinesExtractionTest {

    private static String synthetic;

    @BeforeAll
    static void extractSyntheticDatabase() throws Exception {
        synthetic = extract(ExportTestDatabase.syntheticDatabase("synthetic-routines"), ExtractionOptions.DEFAULT);
    }

    @Test
    void proceduresMatchTheIbexpertReferenceExceptForAllowedDiffs() throws IOException {
        Path golden = ExportTestDatabase.repositoryRoot().resolve("samples/export-db/golden");
        String reference = Files.readString(golden.resolve("export-db.ibexpert.sql"), StandardCharsets.UTF_8);
        AllowedDiffs allowed = AllowedDiffs.load(golden.resolve("allowed-diffs"));
        List<ScriptComparator.Difference> differences =
                ScriptComparator.compare(reference, synthetic, Set.of("PROCEDURE"), allowed);
        assertTrue(differences.isEmpty(), () -> "Divergências da Referência:\n" + differences);
    }

    @Test
    void syntheticRoutinesRoundTripWithSetTerm() throws Exception {
        assertTrue(synthetic.contains("SET TERM ^ ;"), synthetic);
        assertTrue(synthetic.indexOf("/* Routine headers */") < synthetic.indexOf("/* Indices */"), synthetic);
        assertTrue(synthetic.indexOf("/* Indices */") < synthetic.indexOf("/* Routine bodies */"), synthetic);
        ExportTestDatabase empty = ExportTestDatabase.create("routines-roundtrip", "UTF8");
        empty.run(synthetic);
        assertEquals(synthetic, extract(empty, ExtractionOptions.DEFAULT));
    }

    @Test
    void parametersKeepDomainsTypeOfDefaultsNotNullAndSecurity() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-parameters", "UTF8");
        execute(source,
                "CREATE DOMAIN D_NOME AS VARCHAR(30)",
                "CREATE TABLE PESSOA (ID INTEGER NOT NULL PRIMARY KEY, NOME D_NOME, IDADE SMALLINT)",
                """
                CREATE PROCEDURE SP_BUSCA (P_ID INTEGER NOT NULL, P_NOME D_NOME, P_TIPO TYPE OF D_NOME,
                    P_IDADE TYPE OF COLUMN PESSOA.IDADE, P_LIMITE NUMERIC(9,2) DEFAULT 10,
                    P_TXT VARCHAR(10) CHARACTER SET WIN1252 DEFAULT 'x')
                RETURNS (R_NOME D_NOME, R_TOTAL BIGINT NOT NULL, R_OBS BLOB SUB_TYPE TEXT)
                SQL SECURITY DEFINER
                AS
                BEGIN
                  R_NOME = P_NOME;
                  R_TOTAL = P_ID;
                  SUSPEND;
                END""",
                """
                CREATE FUNCTION F_DOBRO (X INTEGER NOT NULL, Y INTEGER DEFAULT 2, Z TYPE OF D_NOME = 'a')
                RETURNS INTEGER NOT NULL
                DETERMINISTIC
                SQL SECURITY INVOKER
                AS
                BEGIN
                  RETURN X * Y;
                END""");
        String script = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(script.contains("P_TIPO TYPE OF D_NOME"), script);
        assertTrue(script.contains("P_IDADE TYPE OF COLUMN PESSOA.IDADE"), script);
        assertTrue(script.contains("P_ID INTEGER NOT NULL"), script);
        assertTrue(script.contains("SQL SECURITY DEFINER"), script);
        assertTrue(script.contains("DETERMINISTIC"), script);
        assertTrue(script.contains("SQL SECURITY INVOKER"), script);
        assertRoundTrip(source, script, "routines-parameters-copy", ExtractionOptions.DEFAULT);
    }

    @Test
    void routineBodyIsExactlyTheSourceStoredInTheDatabase() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-verbatim", "UTF8");
        // Espaços, comentário, caixa e aspas do autor precisam sair como estão, sem normalização.
        String body = """

                  /* comentário -- do autor */
                  declare variable  v  integer;
                BEGIN
                    v = 1;   -- fim da linha
                    n   =  'Aspas ''simples''' ;
                END""";
        execute(source, "CREATE PROCEDURE sp_formatada RETURNS (n VARCHAR(30)) AS" + body);
        String stored = procedureSource(source, "SP_FORMATADA");
        String script = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(script.contains("AS" + stored + "^") || script.contains("AS\n" + stored + "^"),
                "o corpo extraído difere da Fonte do banco:\n" + script);
        ExportTestDatabase copy = assertRoundTrip(source, script, "routines-verbatim-copy", ExtractionOptions.DEFAULT);
        assertEquals(stored, procedureSource(copy, "SP_FORMATADA"));
    }

    @Test
    void oneLineSourceAndTrailingCommentKeepTheSourceAndTheTerminator() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-oneline", "UTF8");
        execute(source,
                "CREATE PROCEDURE SP_UMA AS BEGIN EXIT; END",
                "CREATE PROCEDURE SP_COMENTARIO AS BEGIN EXIT; END -- fim");
        String script = extract(source, ExtractionOptions.DEFAULT);
        ExportTestDatabase copy = assertRoundTrip(source, script, "routines-oneline-copy", ExtractionOptions.DEFAULT);
        assertEquals(procedureSource(source, "SP_UMA"), procedureSource(copy, "SP_UMA"));
        assertEquals(procedureSource(source, "SP_COMENTARIO"), procedureSource(copy, "SP_COMENTARIO"));
    }

    @Test
    void cycleBetweenABodyAndTheViewThatReadsItWarns() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-body-view-cycle", "UTF8");
        execute(source,
                "CREATE PROCEDURE SP_P RETURNS (N INTEGER) AS BEGIN SUSPEND; END",
                "CREATE VIEW V_P (N) AS SELECT N FROM SP_P",
                "ALTER PROCEDURE SP_P RETURNS (N INTEGER) AS BEGIN FOR SELECT COUNT(*) FROM V_P INTO :N DO SUSPEND; END");
        List<String> warnings = new ArrayList<>();
        extract(source, ExtractionOptions.DEFAULT, Selection.all(), warnings(warnings));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("Ciclo entre corpo de rotina e view"), warnings.get(0));
    }

    @Test
    void packageBodyReadingAViewOverARoutineComesAfterTheView() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-package-view", "UTF8");
        execute(source,
                "CREATE PROCEDURE SP_N RETURNS (N INTEGER) AS BEGIN N = 1; SUSPEND; END",
                "CREATE VIEW V_N (N) AS SELECT N FROM SP_N",
                "CREATE PACKAGE PKG AS BEGIN FUNCTION TOTAL RETURNS INTEGER; END",
                """
                CREATE PACKAGE BODY PKG AS
                BEGIN
                  FUNCTION TOTAL RETURNS INTEGER AS
                  BEGIN
                    RETURN (SELECT COUNT(*) FROM V_N);
                  END
                END""");
        String script = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(script.indexOf("CREATE VIEW V_N ") < script.indexOf("CREATE PACKAGE BODY PKG"), script);
        assertRoundTrip(source, script, "routines-package-view-copy", ExtractionOptions.DEFAULT);
    }

    @Test
    void mutuallyCallingProceduresAndAViewOverOneRunOnAnEmptyDatabase() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-cycle", "UTF8");
        execute(source,
                "CREATE TABLE BASE (ID INTEGER NOT NULL PRIMARY KEY)",
                "CREATE PROCEDURE SP_A (N INTEGER) RETURNS (R INTEGER) AS BEGIN SUSPEND; END",
                "CREATE PROCEDURE SP_B (N INTEGER) RETURNS (R INTEGER) AS BEGIN SUSPEND; END",
                """
                ALTER PROCEDURE SP_A (N INTEGER) RETURNS (R INTEGER) AS
                BEGIN
                  IF (N > 0) THEN
                    SELECT R FROM SP_B(:N - 1) INTO :R;
                  ELSE
                    R = 0;
                  SUSPEND;
                END""",
                """
                ALTER PROCEDURE SP_B (N INTEGER) RETURNS (R INTEGER) AS
                BEGIN
                  IF (N > 0) THEN
                    SELECT R FROM SP_A(:N - 1) INTO :R;
                  ELSE
                    R = 1;
                  SUSPEND;
                END""",
                "CREATE VIEW V_A (R) AS SELECT R FROM SP_A(2)",
                "CREATE VIEW V_SOBRE_A (R) AS SELECT R FROM V_A WHERE R > 0",
                "CREATE VIEW V_BASE (ID) AS SELECT ID FROM BASE");
        List<String> warnings = new ArrayList<>();
        String script = extract(source, ExtractionOptions.DEFAULT, Selection.all(), warnings(warnings));
        assertEquals(List.of(), warnings);
        int headers = script.indexOf("/* Routine headers */");
        int bodies = script.indexOf("/* Routine bodies */");
        int views = script.indexOf("/* Views that read routines */");
        assertTrue(script.indexOf("CREATE VIEW V_BASE") < headers, script);
        // O Firebird recusa ALTER PROCEDURE com view sobre view já criada: o corpo vem antes das views que o leem.
        assertTrue(headers < bodies && bodies < views, script);
        assertTrue(views < script.indexOf("CREATE VIEW V_A "), script);
        assertTrue(script.indexOf("CREATE VIEW V_A ") < script.indexOf("CREATE VIEW V_SOBRE_A "), script);
        // Banco vazio, sem criar nada à mão.
        assertRoundTrip(source, script, "routines-cycle-copy", ExtractionOptions.DEFAULT);
    }

    @Test
    void viewsOverFunctionsAndPackagesComeAfterTheirBodies() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-view-function", "UTF8");
        execute(source,
                "CREATE TABLE BASE (ID INTEGER NOT NULL PRIMARY KEY)",
                "CREATE FUNCTION F_UM RETURNS INTEGER AS BEGIN RETURN 1; END",
                """
                CREATE PACKAGE PKG AS
                BEGIN
                  FUNCTION DOIS RETURNS INTEGER;
                END""",
                """
                CREATE PACKAGE BODY PKG AS
                BEGIN
                  FUNCTION DOIS RETURNS INTEGER AS BEGIN RETURN 2; END
                END""",
                "CREATE VIEW V_FUNCAO (ID, UM) AS SELECT ID, F_UM() FROM BASE",
                "CREATE VIEW V_PACOTE (ID, DOIS) AS SELECT ID, PKG.DOIS() FROM BASE");
        List<String> warnings = new ArrayList<>();
        String script = extract(source, ExtractionOptions.DEFAULT, Selection.all(), warnings(warnings));
        assertEquals(List.of(), warnings);
        assertTrue(script.indexOf("/* Routine headers */") < script.indexOf("CREATE VIEW V_FUNCAO"), script);
        assertTrue(script.indexOf("/* Routine bodies */") < script.indexOf("CREATE VIEW V_PACOTE"), script);
        assertRoundTrip(source, script, "routines-view-function-copy", ExtractionOptions.DEFAULT);
    }

    @Test
    void packagesWithPrivateRoutinesRoundTrip() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-packages", "UTF8");
        execute(source,
                """
                CREATE PACKAGE PKG_CALC AS
                BEGIN
                  FUNCTION SOMA (A INTEGER, B INTEGER) RETURNS INTEGER;
                  PROCEDURE LISTA (N INTEGER) RETURNS (V INTEGER);
                END""",
                """
                CREATE PACKAGE BODY PKG_CALC AS
                BEGIN
                  FUNCTION PRIVADA (X INTEGER) RETURNS INTEGER AS
                  BEGIN
                    RETURN X * 10;
                  END
                  FUNCTION SOMA (A INTEGER, B INTEGER) RETURNS INTEGER AS
                  BEGIN
                    RETURN PRIVADA(A) + B;
                  END
                  PROCEDURE LISTA (N INTEGER) RETURNS (V INTEGER) AS
                  BEGIN
                    V = 0;
                    WHILE (V < N) DO
                    BEGIN
                      V = V + 1;
                      SUSPEND;
                    END
                  END
                END""");
        String script = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(script.indexOf("CREATE PACKAGE PKG_CALC") < script.indexOf("CREATE PACKAGE BODY PKG_CALC"), script);
        assertRoundTrip(source, script, "routines-packages-copy", ExtractionOptions.DEFAULT);
    }

    @Test
    void createOrAlterOptionAppliesToProcedureAndFunctionBodies() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-create-or-alter", "UTF8");
        execute(source,
                "CREATE PROCEDURE SP_X AS BEGIN EXIT; END",
                "CREATE FUNCTION F_X RETURNS INTEGER AS BEGIN RETURN 1; END");
        ExtractionOptions options = new ExtractionOptions(false, false, false, false, true);
        String script = extract(source, options);
        assertTrue(script.contains("CREATE PROCEDURE SP_X"), script);
        assertTrue(script.contains("CREATE OR ALTER PROCEDURE SP_X"), script);
        assertTrue(script.contains("CREATE OR ALTER FUNCTION F_X"), script);
        assertFalse(script.contains("\nALTER "), script);
        assertRoundTrip(source, script, "routines-create-or-alter-copy", options);
    }

    @Test
    void withoutSetTermNoStatementUsesItAndEveryRoutineEndsWithEndSemicolon() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("routines-no-set-term");
        ExtractionOptions options = new ExtractionOptions(false, false, false, false, false, true);
        String script = extract(source, options);
        assertFalse(script.toUpperCase().contains("SET TERM"), script);
        assertFalse(script.contains("^\n"), script);
        // Cada rotina fecha com END; e uma linha em branco: é onde um cliente que entende blocos (DBeaver) corta.
        Matcher routines = Pattern.compile("(?m)^(?:CREATE|ALTER) PROCEDURE .*?^END;$", Pattern.DOTALL).matcher(script);
        int count = 0;
        while (routines.find()) {
            count++;
        }
        assertEquals(6, count, script);

        // Para provar que roda: o mesmo texto, com cada bloco de rotinas entre SET TERM, executado num banco vazio.
        ExportTestDatabase target = ExportTestDatabase.create("routines-no-set-term-copy", "UTF8");
        target.run(withSetTermAroundRoutines(script));
        assertEquals(synthetic, extract(target, ExtractionOptions.DEFAULT));
    }

    @Test
    void omitHeaderDropsSetSqlDialectAndSetNamesOnly() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-omit-header", "UTF8");
        execute(source, "CREATE PROCEDURE SP_X AS BEGIN EXIT; END");
        String withHeader = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(withHeader.startsWith("SET SQL DIALECT 3;\nSET NAMES UTF8;\n"), withHeader);

        String script = extract(source, new ExtractionOptions(false, false, false, false, false, false, true));
        assertFalse(script.contains("SET SQL DIALECT"), script);
        assertFalse(script.contains("SET NAMES"), script);
        assertEquals(withHeader.substring("SET SQL DIALECT 3;\nSET NAMES UTF8;\n".length()), script);
    }

    @Test
    void tableObjectsThatCallRoutinesWarn() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("routines-table-users", "UTF8");
        execute(source,
                "CREATE FUNCTION F_UM RETURNS INTEGER AS BEGIN RETURN 1; END",
                "CREATE TABLE T (ID INTEGER NOT NULL PRIMARY KEY, UM COMPUTED BY (F_UM()), NOME VARCHAR(10))",
                "CREATE INDEX IDX_T_UM ON T COMPUTED BY (F_UM() + ID)",
                "CREATE TABLE OUTRA (ID INTEGER NOT NULL PRIMARY KEY)");
        List<String> warnings = new ArrayList<>();
        extract(source, ExtractionOptions.DEFAULT, Selection.all(), warnings(warnings));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("function F_UM (coluna T.UM)"), warnings.get(0));
        assertTrue(warnings.get(0).contains("function F_UM (índice IDX_T_UM)"), warnings.get(0));
    }

    @Test
    void onlySelectedRoutinesAreExtractedAndMissingOnesWarn() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("routines-selection");
        try (Connection connection = source.connect()) {
            MetadataExtractor extractor = new MetadataExtractor(connection);
            assertEquals(List.of("SP_FECHAR_PEDIDO", "SP_LISTA_CLIENTES", "SP_TOTAL_PEDIDO"),
                    extractor.listProcedures());
            assertEquals(List.of(), extractor.listFunctions());
            assertEquals(List.of(), extractor.listPackages());
        }
        String script = extract(source, ExtractionOptions.DEFAULT, new Selection(Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of("SP_TOTAL_PEDIDO"), Set.of(), Set.of()), ExtractionProgress.NONE);
        assertTrue(script.contains("CREATE PROCEDURE SP_TOTAL_PEDIDO"), script);
        assertTrue(script.contains("ALTER PROCEDURE SP_TOTAL_PEDIDO"), script);
        assertFalse(script.contains("SP_FECHAR_PEDIDO"), script);

        // Uma view cuja procedure ficou fora da seleção avisa.
        ExportTestDatabase withView = ExportTestDatabase.create("routines-selection-view", "UTF8");
        execute(withView,
                "CREATE PROCEDURE SP_N RETURNS (N INTEGER) AS BEGIN N = 1; SUSPEND; END",
                "CREATE VIEW V_N (N) AS SELECT N FROM SP_N");
        List<String> warnings = new ArrayList<>();
        extract(withView, ExtractionOptions.DEFAULT, new Selection(Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of("V_N")), warnings(warnings));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("procedure SP_N (view V_N)"), warnings.get(0));
    }

    /** Envolve cada bloco de rotinas (sem {@code SET TERM}) em {@code SET TERM}, que é o que o {@code run} entende. */
    private static String withSetTermAroundRoutines(String script) {
        Matcher block = Pattern.compile("(?s)(/\\* (?:Routine headers|Routine bodies|Triggers) \\*/\\n\\n)(.*?)(?=\\n/\\* |\\z)")
                .matcher(script);
        StringBuilder result = new StringBuilder();
        while (block.find()) {
            String converted = block.group(2).replace("END;\n\n", "END^\n\n");
            block.appendReplacement(result, Matcher.quoteReplacement(block.group(1) + "SET TERM ^ ;\n\n" + converted
                    + "SET TERM ; ^\n"));
        }
        block.appendTail(result);
        return result.toString();
    }

    private static ExportTestDatabase assertRoundTrip(ExportTestDatabase source, String script, String copyName,
            ExtractionOptions options) throws Exception {
        ExportTestDatabase target = ExportTestDatabase.create(copyName, "UTF8");
        target.run(script);
        assertEquals(script, extract(target, options));
        return target;
    }

    private static String procedureSource(ExportTestDatabase database, String name) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT RDB$PROCEDURE_SOURCE FROM RDB$PROCEDURES WHERE RDB$PROCEDURE_NAME = '" + name + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static ExtractionProgress warnings(List<String> warnings) {
        return new ExtractionProgress() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        };
    }

    private static String extract(ExportTestDatabase database, ExtractionOptions options)
            throws SQLException, IOException {
        return extract(database, options, Selection.all(), ExtractionProgress.NONE);
    }

    private static String extract(ExportTestDatabase database, ExtractionOptions options, Selection selection,
            ExtractionProgress progress) throws SQLException, IOException {
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new MetadataExtractor(connection).extract(selection, options, out, null, progress);
            return out.toString();
        }
    }

    private static void execute(ExportTestDatabase database, String... statements) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
