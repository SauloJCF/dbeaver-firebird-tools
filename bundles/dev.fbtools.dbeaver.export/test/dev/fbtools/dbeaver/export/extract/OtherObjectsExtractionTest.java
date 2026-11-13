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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.export.testing.AllowedDiffs;
import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;
import dev.fbtools.dbeaver.export.testing.ScriptComparator;

/**
 * Issue 06: triggers, exceptions, UDFs, collations, BLOB filters, roles, sequences, mappings e publication. O banco
 * sintético contra a Referência do IBExpert (subconjunto comum) e ida e volta com o que só o Firebird 5 tem. Exige o
 * Firebird 5 local (ADR 0008).
 */
class OtherObjectsExtractionTest {

    private static String synthetic;
    private static String syntheticWithFirebird5;

    @BeforeAll
    static void extractSyntheticDatabases() throws Exception {
        synthetic = extract(ExportTestDatabase.syntheticDatabase("synthetic-objects"), ExtractionOptions.DEFAULT);
        syntheticWithFirebird5 = extract(ExportTestDatabase.syntheticDatabaseWithFirebird5Objects("synthetic-fb5"),
                ExtractionOptions.DEFAULT);
    }

    @Test
    void commonSubsetMatchesTheIbexpertReferenceExceptForAllowedDiffs() throws IOException {
        Path golden = ExportTestDatabase.repositoryRoot().resolve("samples/export-db/golden");
        String reference = Files.readString(golden.resolve("export-db.ibexpert.sql"), StandardCharsets.UTF_8);
        AllowedDiffs allowed = AllowedDiffs.load(golden.resolve("allowed-diffs"));
        List<ScriptComparator.Difference> differences = ScriptComparator.compare(reference, synthetic,
                Set.of("TRIGGER", "EXCEPTION", "ROLE", "GENERATOR"), allowed);
        assertTrue(differences.isEmpty(), () -> "Divergências da Referência:\n" + differences);
    }

    @Test
    void syntheticDatabaseRoundTripsOnAnEmptyDatabase() throws Exception {
        ExportTestDatabase empty = ExportTestDatabase.create("objects-roundtrip", "UTF8");
        empty.run(synthetic);
        assertEquals(synthetic, extract(empty, ExtractionOptions.DEFAULT));
    }

    @Test
    void firebird5ObjectsRoundTrip() throws Exception {
        for (String expected : List.of(
                "CREATE SEQUENCE SEQ_PASSO START WITH 100 INCREMENT BY 5;",
                "CREATE SEQUENCE SEQ_DESCENDO START WITH 50 INCREMENT BY -5;",
                "CREATE SEQUENCE SEQ_INICIO START WITH 1000;",
                "SET GENERATOR SEQ_PASSO TO 120;",
                "CREATE COLLATION UNICODE_NUMERICO FOR UTF8 FROM UNICODE PAD SPACE CASE INSENSITIVE ACCENT SENSITIVE"
                        + " 'NUMERIC-SORT=1';",
                "DECLARE FILTER FILTRO_TEXTO INPUT_TYPE 1 OUTPUT_TYPE -4 ENTRY_POINT 'filtro_texto' MODULE_NAME 'filtros';",
                "RETURNS CSTRING(20) FREE_IT",
                "RETURNS PARAMETER 2",
                "RETURNS INTEGER BY VALUE",
                "INTEGER BY SCALAR_ARRAY",
                "CREATE ROLE ADMIN_USUARIOS SET SYSTEM PRIVILEGES TO USER_MANAGEMENT, MONITOR_ANY_ATTACHMENT;",
                "CREATE MAPPING MAP_ENCADEADO USING MAPPING FROM ANY USER TO ROLE VENDEDOR;",
                "ACTIVE ON CONNECT POSITION 0",
                "INACTIVE ON TRANSACTION COMMIT POSITION 5",
                "INACTIVE BEFORE ANY DDL STATEMENT POSITION 0",
                "INACTIVE AFTER CREATE TABLE OR ALTER TABLE OR DROP TABLE POSITION 5",
                "ACTIVE AFTER INSERT OR UPDATE OR DELETE POSITION 40",
                "ALTER DATABASE INCLUDE TABLE CLIENTE, PEDIDO TO PUBLICATION;",
                "ALTER DATABASE ENABLE PUBLICATION;")) {
            assertTrue(syntheticWithFirebird5.contains(expected), "faltou: " + expected + "\n" + syntheticWithFirebird5);
        }
        ExportTestDatabase empty = ExportTestDatabase.create("objects-fb5-roundtrip", "UTF8");
        empty.run(syntheticWithFirebird5);
        assertEquals(syntheticWithFirebird5, extract(empty, ExtractionOptions.DEFAULT));
    }

    @Test
    void collationsKeepAllThreeAttributesAndTheIcuSpecification() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabaseWithFirebird5Objects("objects-collations");
        ExportTestDatabase copy = ExportTestDatabase.create("objects-collations-copy", "UTF8");
        copy.run(syntheticWithFirebird5);
        String query = "SELECT RDB$COLLATION_NAME, RDB$COLLATION_ATTRIBUTES, RDB$BASE_COLLATION_NAME,"
                + " RDB$SPECIFIC_ATTRIBUTES FROM RDB$COLLATIONS WHERE RDB$SYSTEM_FLAG = 0 ORDER BY 1";
        assertEquals(rows(source, query), rows(copy, query));
        assertEquals(3, rows(copy, query).size());
    }

    @Test
    void sequenceKeepsStartIncrementAndTheCurrentValue() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabaseWithFirebird5Objects("objects-sequences");
        ExportTestDatabase copy = ExportTestDatabase.create("objects-sequences-copy", "UTF8");
        copy.run(syntheticWithFirebird5);
        String query = "SELECT RDB$GENERATOR_NAME, RDB$INITIAL_VALUE, RDB$GENERATOR_INCREMENT FROM RDB$GENERATORS"
                + " WHERE RDB$SYSTEM_FLAG = 0 ORDER BY 1";
        assertEquals(rows(source, query), rows(copy, query));
        // SEQ_PASSO está em 120 e anda de 5 em 5; SEQ_INICIO ainda não foi usada e começa em 1000.
        assertEquals(List.of("125"), rows(copy, "SELECT NEXT VALUE FOR SEQ_PASSO FROM RDB$DATABASE"));
        assertEquals(List.of("1000"), rows(copy, "SELECT NEXT VALUE FOR SEQ_INICIO FROM RDB$DATABASE"));
    }

    @Test
    void useSequenceOptionTurnsEveryGeneratorIntoASequence() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("objects-use-sequence");
        ExtractionOptions options = new ExtractionOptions(false, false, false, false, false, false, false, true);
        String script = extract(source, options);
        assertTrue(script.contains("CREATE SEQUENCE GEN_PEDIDO;"), script);
        assertFalse(script.contains("CREATE GENERATOR"), script);
        ExportTestDatabase copy = ExportTestDatabase.create("objects-use-sequence-copy", "UTF8");
        copy.run(script);
        assertEquals(script, extract(copy, options));
    }

    @Test
    void setGeneratorComesAfterTheTriggers() {
        assertTrue(synthetic.lastIndexOf("CREATE TRIGGER") < synthetic.indexOf("SET GENERATOR"), synthetic);
        assertTrue(synthetic.indexOf("CREATE GENERATOR") < synthetic.indexOf("/* Tables */"), synthetic);
    }

    @Test
    void exceptionsComeBeforeTheBodiesThatRaiseThem() {
        assertTrue(synthetic.indexOf("CREATE EXCEPTION E_PEDIDO_FECHADO") < synthetic.indexOf("/* Routine bodies */"),
                synthetic);
    }

    @Test
    void triggersComeAfterTheLastBodyAndTheViewsThatReadRoutines() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("objects-trigger-order", "UTF8");
        execute(source,
                "CREATE TABLE BASE (ID INTEGER NOT NULL PRIMARY KEY, N INTEGER)",
                "CREATE PROCEDURE SP_N RETURNS (N INTEGER) AS BEGIN N = 1; SUSPEND; END",
                "CREATE VIEW V_N (N) AS SELECT N FROM SP_N",
                "CREATE FUNCTION F_UM RETURNS INTEGER AS BEGIN RETURN 1; END",
                """
                CREATE TRIGGER TR_BASE_BI FOR BASE ACTIVE BEFORE INSERT POSITION 0
                AS
                BEGIN
                  NEW.N = (SELECT MAX(N) FROM V_N) + F_UM();
                END""");
        String script = extract(source, ExtractionOptions.DEFAULT);
        int trigger = script.indexOf("CREATE TRIGGER TR_BASE_BI");
        assertTrue(script.lastIndexOf("/* Routine bodies */") < trigger, script);
        assertTrue(script.indexOf("CREATE VIEW V_N") < trigger, script);
        ExportTestDatabase copy = ExportTestDatabase.create("objects-trigger-order-copy", "UTF8");
        copy.run(script);
        assertEquals(script, extract(copy, ExtractionOptions.DEFAULT));
    }

    @Test
    void triggerSourceIsExactlyTheOneStoredInTheDatabase() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("objects-trigger-verbatim", "UTF8");
        execute(source,
                "CREATE TABLE T (ID INTEGER, NOME VARCHAR(10))",
                """
                CREATE TRIGGER tr_formatado FOR t ACTIVE BEFORE INSERT POSITION 3
                AS
                  /* comentário -- do autor */
                  declare variable  v  integer;
                BEGIN
                    v = 1;   -- fim da linha
                    new.nome  =  'Aspas ''simples''' ;
                END""");
        String stored = triggerSource(source, "TR_FORMATADO");
        String script = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(script.contains("POSITION 3\n" + stored.strip() + "\n^") || script.contains("POSITION 3\n" + stored.strip() + "^"),
                "a Fonte extraída difere da do banco:\n" + script);
        ExportTestDatabase copy = ExportTestDatabase.create("objects-trigger-verbatim-copy", "UTF8");
        copy.run(script);
        assertEquals(stored, triggerSource(copy, "TR_FORMATADO"));
    }

    @Test
    void createOrAlterAppliesToTriggers() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("objects-create-or-alter");
        ExtractionOptions options = new ExtractionOptions(false, false, false, false, true);
        String script = extract(source, options);
        assertTrue(script.contains("CREATE OR ALTER TRIGGER TR_CLIENTE_BI FOR CLIENTE"), script);
        ExportTestDatabase copy = ExportTestDatabase.create("objects-create-or-alter-copy", "UTF8");
        copy.run(script);
        assertEquals(script, extract(copy, options));
    }

    @Test
    void checkConstraintTriggersAndSystemObjectsAreNotListed() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabaseWithFirebird5Objects("objects-listing");
        try (Connection connection = source.connect()) {
            MetadataExtractor extractor = new MetadataExtractor(connection);
            assertEquals(List.of("E_CLIENTE_BLOQUEADO", "E_ESTOQUE_INSUFICIENTE", "E_PEDIDO_FECHADO"),
                    extractor.listExceptions());
            assertEquals(List.of("ADMIN_USUARIOS", "GERENTE", "VENDEDOR"), extractor.listRoles());
            assertEquals(9 + 6, extractor.listTriggers().size());
            assertEquals(List.of("FILTRO_TEXTO"), extractor.listFilters());
            assertEquals(6, extractor.listUdfs().size());
            assertEquals(3, extractor.listCollations().size());
            assertEquals(List.of("RDB$DEFAULT"), extractor.listPublications());
            assertEquals(4, extractor.listMappings().size());
        }
    }

    @Test
    void onlySelectedObjectsAreExtracted() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabaseWithFirebird5Objects("objects-selection");
        Selection selection = new Selection(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of("E_PEDIDO_FECHADO"), Set.of("UDF_ZERO"), Set.of("UNICODE_NUMERICO"), Set.of(),
                Set.of("GERENTE"), Set.of("TR_BANCO_CONECTA"), Set.of(), Set.of("MAP_SRP"));
        String script = extract(source, ExtractionOptions.DEFAULT, selection, ExtractionProgress.NONE);
        assertTrue(script.contains("CREATE EXCEPTION E_PEDIDO_FECHADO"), script);
        assertFalse(script.contains("E_CLIENTE_BLOQUEADO"), script);
        assertTrue(script.contains("DECLARE EXTERNAL FUNCTION UDF_ZERO"), script);
        assertFalse(script.contains("UDF_TEXTO"), script);
        assertTrue(script.contains("CREATE COLLATION UNICODE_NUMERICO"), script);
        assertFalse(script.contains("UNICODE_SEM_ACENTO"), script);
        assertTrue(script.contains("CREATE ROLE GERENTE;"), script);
        assertFalse(script.contains("VENDEDOR"), script);
        assertTrue(script.contains("CREATE TRIGGER TR_BANCO_CONECTA"), script);
        assertFalse(script.contains("TR_CATEGORIA"), script);
        assertTrue(script.contains("CREATE MAPPING MAP_SRP"), script);
        assertFalse(script.contains("MAP_PAPEL"), script);
        assertFalse(script.contains("PUBLICATION"), script);
    }

    @Test
    void publicationWithAutoEnableIncludesAllAndExcludesTheRest() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("objects-publication", "UTF8");
        execute(source,
                "CREATE TABLE A (ID INTEGER)", "CREATE TABLE B (ID INTEGER)", "CREATE TABLE C (ID INTEGER)",
                "ALTER DATABASE INCLUDE ALL TO PUBLICATION",
                "ALTER DATABASE EXCLUDE TABLE B FROM PUBLICATION");
        String script = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(script.contains("ALTER DATABASE INCLUDE ALL TO PUBLICATION;\n"
                + "ALTER DATABASE EXCLUDE TABLE B FROM PUBLICATION;"), script);
        assertFalse(script.contains("ENABLE PUBLICATION"), script);
        ExportTestDatabase copy = ExportTestDatabase.create("objects-publication-copy", "UTF8");
        copy.run(script);
        assertEquals(script, extract(copy, ExtractionOptions.DEFAULT));
        // Sem publication configurada, nada sai e o grupo não aparece.
        ExportTestDatabase plain = ExportTestDatabase.create("objects-no-publication", "UTF8");
        try (Connection connection = plain.connect()) {
            assertEquals(List.of(), new MetadataExtractor(connection).listPublications());
        }
        assertFalse(extract(plain, ExtractionOptions.DEFAULT).contains("PUBLICATION"));
    }

    @Test
    void autoEnabledPublicationWithoutSelectedTablesDoesNotIncludeAll() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("objects-publication-no-tables", "UTF8");
        execute(source, "CREATE TABLE A (ID INTEGER)", "ALTER DATABASE INCLUDE ALL TO PUBLICATION",
                "ALTER DATABASE ENABLE PUBLICATION");
        Selection onlyPublication = new Selection(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of("RDB$DEFAULT"), Set.of());
        String script = extract(source, ExtractionOptions.DEFAULT, onlyPublication, ExtractionProgress.NONE);
        assertFalse(script.contains("INCLUDE ALL"), script);
        assertTrue(script.contains("ALTER DATABASE ENABLE PUBLICATION;"), script);
    }

    @Test
    void roleWithoutSystemPrivilegesAndWithQuotedNamesRoundTrip() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("objects-quoted", "UTF8");
        execute(source,
                "CREATE ROLE \"Papel Misto\"",
                "CREATE EXCEPTION \"Erro Estranho\" 'Mensagem com ''aspas'' e acentuação: ç ã'");
        String script = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(script.contains("CREATE ROLE \"Papel Misto\";"), script);
        assertTrue(script.contains("CREATE EXCEPTION \"Erro Estranho\" 'Mensagem com ''aspas'' e acentuação: ç ã';"),
                script);
        ExportTestDatabase copy = ExportTestDatabase.create("objects-quoted-copy", "UTF8");
        copy.run(script);
        assertEquals(script, extract(copy, ExtractionOptions.DEFAULT));
    }

    private static List<String> rows(ExportTestDatabase database, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(query)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    String value = rs.getString(i);
                    cells.add(value == null ? "null" : value.strip());
                }
                rows.add(String.join("|", cells));
            }
        }
        return rows;
    }

    private static String triggerSource(ExportTestDatabase database, String name) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT RDB$TRIGGER_SOURCE FROM RDB$TRIGGERS WHERE RDB$TRIGGER_NAME = '" + name + "'")) {
            rs.next();
            return rs.getString(1);
        }
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
