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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.export.testing.AllowedDiffs;
import dev.fbtools.dbeaver.export.testing.ExportTestDatabase;
import dev.fbtools.dbeaver.export.testing.ScriptComparator;

/**
 * Issue 07: descrições ({@code COMMENT ON}), grants e as opções de metadata. O banco sintético contra a Referência do
 * IBExpert (subconjunto comum), ida e volta com o que só o Firebird 5 tem e um teste por opção que liga e desliga.
 * Exige o Firebird 5 local (ADR 0008).
 */
class GrantsAndDescriptionsExtractionTest {

    /** O que o sintético não tem: descrições de todos os tipos e privilégios de cada forma. */
    private static final String EXTRAS = """
            SET TERM ^ ;
            CREATE FUNCTION FN_DOBRO (X INTEGER) RETURNS INTEGER AS BEGIN RETURN X * 2; END^
            CREATE PACKAGE PKG_CALC AS BEGIN FUNCTION SOMA (A INTEGER, B INTEGER) RETURNS INTEGER; END^
            CREATE PACKAGE BODY PKG_CALC AS BEGIN
              FUNCTION SOMA (A INTEGER, B INTEGER) RETURNS INTEGER AS BEGIN RETURN A + B; END
            END^
            SET TERM ; ^
            CREATE ROLE AUDITOR;
            COMMENT ON VIEW V_CLIENTE_ATIVO IS 'Clientes ativos';
            COMMENT ON COLUMN V_CLIENTE_ATIVO.CIDADE IS 'Cidade d''o cadastro';
            COMMENT ON COLUMN CLIENTE.NOME IS 'Primeira linha
            segunda linha';
            COMMENT ON TRIGGER TR_CLIENTE_BI IS 'Preenche o ID';
            COMMENT ON INDEX IDX_CLIENTE_NOME IS 'Busca por nome';
            COMMENT ON PARAMETER SP_TOTAL_PEDIDO.ID_PEDIDO IS 'Pedido a somar';
            COMMENT ON ROLE VENDEDOR IS 'Quem vende';
            COMMENT ON COLLATION UNICODE_NUMERICO IS 'Ordena números';
            COMMENT ON FILTER FILTRO_TEXTO IS 'Texto para binário';
            COMMENT ON EXTERNAL FUNCTION UDF_ZERO IS 'Sempre zero';
            COMMENT ON FUNCTION FN_DOBRO IS 'Dobra o valor';
            COMMENT ON PACKAGE PKG_CALC IS 'Contas';
            COMMENT ON TABLE CATEGORIA IS 'Categorias';
            GRANT EXECUTE ON FUNCTION FN_DOBRO TO ROLE VENDEDOR;
            GRANT EXECUTE ON PACKAGE PKG_CALC TO USER MARIA;
            GRANT USAGE ON EXCEPTION E_PEDIDO_FECHADO TO ROLE VENDEDOR;
            GRANT USAGE ON SEQUENCE GEN_PEDIDO TO ROLE VENDEDOR;
            GRANT SELECT ON CLIENTE TO PROCEDURE SP_LISTA_CLIENTES;
            GRANT SELECT ON CLIENTE TO TRIGGER TR_CLIENTE_BI;
            GRANT SELECT ON CLIENTE TO VIEW V_CLIENTE_ATIVO;
            GRANT AUDITOR TO ROLE VENDEDOR WITH ADMIN OPTION;
            GRANT VENDEDOR TO USER MARIA WITH ADMIN OPTION;
            GRANT SELECT, REFERENCES (ID) ON CATEGORIA TO USER MARIA WITH GRANT OPTION;
            GRANT DELETE ON LOG TO PUBLIC;
            """;

    private static String synthetic;
    private static String extended;

    @BeforeAll
    static void extractDatabases() throws Exception {
        synthetic = extract(ExportTestDatabase.syntheticDatabase("grants-synthetic"), ExtractionOptions.DEFAULT);
        extended = extract(extendedDatabase("grants-extended"), ExtractionOptions.DEFAULT);
    }

    private static ExportTestDatabase extendedDatabase(String name) throws Exception {
        ExportTestDatabase database = ExportTestDatabase.syntheticDatabaseWithFirebird5Objects(name);
        database.run(EXTRAS);
        return database;
    }

    @Test
    void descriptionsAndGrantsMatchTheIbexpertReferenceExceptForAllowedDiffs() throws IOException {
        Path golden = ExportTestDatabase.repositoryRoot().resolve("samples/export-db/golden");
        String reference = Files.readString(golden.resolve("export-db.ibexpert.sql"), StandardCharsets.UTF_8);
        AllowedDiffs allowed = AllowedDiffs.load(golden.resolve("allowed-diffs"));
        List<ScriptComparator.Difference> differences =
                ScriptComparator.compare(reference, synthetic, Set.of("DESCRIPTION", "GRANT"), allowed);
        assertTrue(differences.isEmpty(), () -> "Divergências da Referência:\n" + differences);
        List<String> unused = allowed.unused().stream()
                .filter(entry -> entry.startsWith("GRANT ") || entry.startsWith("DESCRIPTION ")).toList();
        assertEquals(List.of(), unused, "allowed-diffs com entradas sem uso");
    }

    @Test
    void syntheticDatabaseRoundTripsWithDescriptionsAndGrants() throws Exception {
        assertTrue(synthetic.contains("COMMENT ON TABLE CLIENTE IS 'Cadastro de clientes';"), synthetic);
        assertTrue(synthetic.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON ITEM_PEDIDO TO ROLE VENDEDOR"
                + " WITH GRANT OPTION;"), synthetic);
        assertTrue(synthetic.contains("GRANT ALL ON LOG TO ROLE GERENTE;"), synthetic);
        assertTrue(synthetic.contains("GRANT UPDATE (ESTOQUE, PRECO) ON PRODUTO TO ROLE GERENTE;"), synthetic);
        assertTrue(synthetic.contains("GRANT EXECUTE ON PROCEDURE SP_FECHAR_PEDIDO TO ROLE VENDEDOR;"), synthetic);
        assertTrue(synthetic.contains("GRANT VENDEDOR TO USER SYSDBA;"), synthetic);
        assertTrue(synthetic.contains("GRANT SELECT ON V_CLIENTE_ATIVO TO PUBLIC;"), synthetic);
        assertTrue(synthetic.indexOf("/* Descriptions */") < synthetic.indexOf("/* Grants */"), synthetic);
        ExportTestDatabase empty = ExportTestDatabase.create("grants-roundtrip", "UTF8");
        empty.run(synthetic);
        assertEquals(synthetic, extract(empty, ExtractionOptions.DEFAULT));
    }

    @Test
    void descriptionsOfEveryObjectTypeRoundTrip() throws Exception {
        for (String expected : List.of(
                "COMMENT ON VIEW V_CLIENTE_ATIVO IS 'Clientes ativos';",
                "COMMENT ON COLUMN V_CLIENTE_ATIVO.CIDADE IS 'Cidade d''o cadastro';",
                "COMMENT ON COLUMN CLIENTE.NOME IS 'Primeira linha\nsegunda linha';",
                "COMMENT ON TRIGGER TR_CLIENTE_BI IS 'Preenche o ID';",
                "COMMENT ON INDEX IDX_CLIENTE_NOME IS 'Busca por nome';",
                "COMMENT ON PARAMETER SP_TOTAL_PEDIDO.ID_PEDIDO IS 'Pedido a somar';",
                "COMMENT ON ROLE VENDEDOR IS 'Quem vende';",
                "COMMENT ON COLLATION UNICODE_NUMERICO IS 'Ordena números';",
                "COMMENT ON FILTER FILTRO_TEXTO IS 'Texto para binário';",
                "COMMENT ON EXTERNAL FUNCTION UDF_ZERO IS 'Sempre zero';",
                "COMMENT ON FUNCTION FN_DOBRO IS 'Dobra o valor';",
                "COMMENT ON PACKAGE PKG_CALC IS 'Contas';",
                "COMMENT ON TABLE CATEGORIA IS 'Categorias';")) {
            assertTrue(extended.contains(expected), "faltou: " + expected + "\n" + extended);
        }
        ExportTestDatabase empty = ExportTestDatabase.create("descriptions-roundtrip", "UTF8");
        empty.run(extended);
        assertEquals(extended, extract(empty, ExtractionOptions.DEFAULT));
    }

    @Test
    void grantsOfEveryKindRoundTrip() throws Exception {
        for (String expected : List.of(
                "GRANT EXECUTE ON FUNCTION FN_DOBRO TO ROLE VENDEDOR;",
                "GRANT EXECUTE ON PACKAGE PKG_CALC TO USER MARIA;",
                "GRANT USAGE ON EXCEPTION E_PEDIDO_FECHADO TO ROLE VENDEDOR;",
                "GRANT USAGE ON GENERATOR GEN_PEDIDO TO ROLE VENDEDOR;",
                "GRANT SELECT ON CLIENTE TO PROCEDURE SP_LISTA_CLIENTES;",
                "GRANT SELECT ON CLIENTE TO TRIGGER TR_CLIENTE_BI;",
                "GRANT SELECT ON CLIENTE TO VIEW V_CLIENTE_ATIVO;",
                "GRANT AUDITOR TO ROLE VENDEDOR WITH ADMIN OPTION;",
                "GRANT VENDEDOR TO USER MARIA WITH ADMIN OPTION;",
                "GRANT SELECT, REFERENCES (ID) ON CATEGORIA TO USER MARIA WITH GRANT OPTION;",
                "GRANT DELETE ON LOG TO PUBLIC;")) {
            assertTrue(extended.contains(expected), "faltou: " + expected + "\n" + extended);
        }
    }

    @Test
    void ownerPrivilegesAndSystemPrivilegesStayOutOfTheScript() {
        assertFalse(synthetic.contains("RDB$"), synthetic);
        assertFalse(synthetic.contains("GRANT USAGE ON"), synthetic);
        assertFalse(synthetic.contains("TO USER SYSDBA WITH GRANT OPTION"), synthetic);
    }

    @Test
    void setGeneratorsOptionTurnsTheValuesOnAndOff() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabaseWithFirebird5Objects("grants-set-generators");
        String on = extract(source, ExtractionOptions.DEFAULT.withSetGenerators(true));
        String off = extract(source, ExtractionOptions.DEFAULT.withSetGenerators(false));
        assertTrue(on.contains("SET GENERATOR GEN_PEDIDO TO 1000;"), on);
        assertTrue(on.contains("/* Set generators */"), on);
        assertFalse(off.contains("SET GENERATOR"), off);
        assertFalse(off.contains("/* Set generators */"), off);
        assertTrue(off.contains("CREATE GENERATOR GEN_PEDIDO;"), off);
    }

    @Test
    void setGeneratorsOptionAlsoControlsTheIdentityRestart() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("grants-identity", "UTF8");
        source.run("CREATE TABLE ITEM (ID INTEGER GENERATED BY DEFAULT AS IDENTITY, NOME VARCHAR(10));\n"
                + "INSERT INTO ITEM (NOME) VALUES ('a');\nINSERT INTO ITEM (NOME) VALUES ('b');");
        assertTrue(extract(source, ExtractionOptions.DEFAULT.withSetGenerators(true))
                .contains("ALTER TABLE ITEM ALTER COLUMN ID RESTART WITH 3;"));
        assertFalse(extract(source, ExtractionOptions.DEFAULT.withSetGenerators(false)).contains("RESTART WITH"));
    }

    @Test
    void descriptionsOptionTurnsCommentOnOffEverywhere() throws Exception {
        ExportTestDatabase source = extendedDatabase("grants-descriptions");
        String off = extract(source, ExtractionOptions.DEFAULT.withDescriptions(false));
        assertFalse(off.contains("COMMENT ON"), off);
        assertFalse(off.contains("/* Descriptions */"), off);
        assertTrue(extract(source, ExtractionOptions.DEFAULT.withDescriptions(true)).contains("COMMENT ON"));
    }

    @Test
    void descriptionsOnlyOfTheSelectedObjects() throws Exception {
        ExportTestDatabase source = extendedDatabase("grants-descriptions-selection");
        Selection onlyClientes = new Selection(Set.of(), Set.of(), Set.of("CLIENTE"), Set.of());
        String script = extract(source, ExtractionOptions.DEFAULT, onlyClientes);
        assertTrue(script.contains("COMMENT ON TABLE CLIENTE IS"), script);
        assertTrue(script.contains("COMMENT ON COLUMN CLIENTE.LIMITE IS"), script);
        assertFalse(script.contains("COMMENT ON TABLE CATEGORIA"), script);
        assertFalse(script.contains("COMMENT ON DOMAIN"), script);
        assertFalse(script.contains("COMMENT ON PROCEDURE"), script);
    }

    @Test
    void grantsOptionTurnsPrivilegesOnAndOff() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("grants-on-off");
        assertTrue(extract(source, ExtractionOptions.DEFAULT.withGrants(true, true)).contains("GRANT "));
        String off = extract(source, ExtractionOptions.DEFAULT.withGrants(false, true));
        assertFalse(off.contains("GRANT "), off);
        assertFalse(off.contains("/* Grants */"), off);
    }

    @Test
    void grantsOnlyOfTheSelectedObjectsWhenAsked() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("grants-selection");
        Selection onlyClientes = selection(Set.of("CLIENTE"), Set.of("VENDEDOR"));
        String restricted = extract(source, ExtractionOptions.DEFAULT.withGrants(true, true), onlyClientes);
        assertTrue(restricted.contains("GRANT SELECT ON CLIENTE TO ROLE VENDEDOR;"), restricted);
        assertFalse(restricted.contains(" ON PEDIDO "), restricted);
        assertFalse(restricted.contains(" ON PROCEDURE "), restricted);
        assertTrue(restricted.contains("GRANT VENDEDOR TO USER SYSDBA;"), restricted);

        String everything = extract(source, ExtractionOptions.DEFAULT.withGrants(true, false), onlyClientes);
        assertTrue(everything.contains("GRANT SELECT ON CLIENTE TO ROLE VENDEDOR;"), everything);
        assertTrue(everything.contains(" ON PEDIDO TO ROLE VENDEDOR;"), everything);
        assertTrue(everything.contains("GRANT EXECUTE ON PROCEDURE SP_FECHAR_PEDIDO TO ROLE VENDEDOR;"), everything);
    }

@Test
void grantsToAnObjectOutsideTheSelectionAreLeftOut() throws Exception {
    ExportTestDatabase source = extendedDatabase("grants-grantee-selection");
    Selection onlyClientes = selection(Set.of("CLIENTE"), Set.of());
    String script = extract(source, ExtractionOptions.DEFAULT, onlyClientes);
    assertFalse(script.contains("TO ROLE VENDEDOR"), script);
    assertFalse(script.contains("TO PROCEDURE") || script.contains("TO TRIGGER") || script.contains("TO VIEW"),
            script);
    String all = extract(source, ExtractionOptions.DEFAULT.withGrants(true, false), onlyClientes);
    assertTrue(all.contains("TO PROCEDURE SP_LISTA_CLIENTES"), all);
}

@Test
void warningsOfUnsupportedPrivilegesIgnoreObjectsOutsideTheSelection() throws Exception {
    ExportTestDatabase source = ExportTestDatabase.create("grants-warnings", "UTF8");
    source.run("CREATE TABLE A (ID INTEGER);\nCREATE TABLE B (ID INTEGER);\nGRANT CREATE TABLE TO USER MARIA;");
    List<String> warnings = new ArrayList<>();
    extract(source, ExtractionOptions.DEFAULT, selection(Set.of("A"), Set.of()), warnings);
    assertTrue(warnings.stream().noneMatch(w -> w.contains("Privilégios que a extração não gera")),
            warnings.toString());
    warnings.clear();
    extract(source, ExtractionOptions.DEFAULT.withGrants(true, false), selection(Set.of("A"), Set.of()), warnings);
    assertTrue(warnings.stream().anyMatch(w -> w.contains("Privilégios que a extração não gera")),
            warnings.toString());
}

@Test
void decodeDomainsKeepsNotNullOfRoutineParametersAndWarnsAboutLostChecks() throws Exception {
    ExportTestDatabase source = ExportTestDatabase.create("grants-decode-params", "UTF8");
    source.run("""
            CREATE DOMAIN D_POSITIVO AS INTEGER NOT NULL CHECK (VALUE > 0);
            SET TERM ^ ;
            CREATE PROCEDURE SP_Q (X D_POSITIVO) RETURNS (Y D_POSITIVO) AS BEGIN Y = X; SUSPEND; END^
            SET TERM ; ^
            """);
    List<String> warnings = new ArrayList<>();
    String decoded = extract(source, ExtractionOptions.DEFAULT.withDecodeDomains(true), Selection.all(), warnings);
    assertTrue(decoded.contains("X INTEGER NOT NULL"), decoded);
    assertTrue(decoded.contains("Y INTEGER NOT NULL"), decoded);
    assertTrue(warnings.stream().anyMatch(w -> w.contains("parâmetro") && w.contains("SP_Q.X")),
            warnings.toString());
}

@Test
void warnsAboutDescriptionsOfFunctionParametersAndPackagedRoutines() throws Exception {
    ExportTestDatabase source = ExportTestDatabase.create("grants-skipped-descriptions", "UTF8");
    source.run("""
            SET TERM ^ ;
            CREATE PACKAGE PKG AS BEGIN PROCEDURE P; END^
            CREATE PACKAGE BODY PKG AS BEGIN PROCEDURE P AS BEGIN EXIT; END END^
            SET TERM ; ^
            COMMENT ON PROCEDURE PKG.P IS 'Dentro do package';
            """);
    List<String> warnings = new ArrayList<>();
    extract(source, ExtractionOptions.DEFAULT, Selection.all(), warnings);
    assertTrue(warnings.stream().anyMatch(w -> w.contains("descrições") && w.contains("packages")),
            warnings.toString());
}

private static Selection selection(Set<String> tables, Set<String> roles) {
    return new Selection(Set.of(), Set.of(), tables, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), Set.of(), roles, Set.of(), Set.of(), Set.of());
}

@Test
void grantsOfARoleSelectedTogetherWithItsMembershipRoundTripOnAPartialExtraction() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("grants-roles");
        Selection roles = new Selection(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of("VENDEDOR"), Set.of(), Set.of(), Set.of());
        String script = extract(source, ExtractionOptions.DEFAULT, roles);
        assertTrue(script.contains("CREATE ROLE VENDEDOR;"), script);
        assertTrue(script.contains("GRANT VENDEDOR TO USER SYSDBA;"), script);
    }

    @Test
    void roundTripWithGrantsRestrictedToTheSelectedObjects() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("grants-restricted", "UTF8");
        source.run("""
                CREATE ROLE PAPEL;
                CREATE TABLE A (ID INTEGER NOT NULL PRIMARY KEY);
                CREATE TABLE B (ID INTEGER NOT NULL PRIMARY KEY);
                COMMENT ON TABLE A IS 'Tabela A';
                COMMENT ON TABLE B IS 'Tabela B';
                GRANT SELECT ON A TO ROLE PAPEL;
                GRANT SELECT ON B TO ROLE PAPEL;
                GRANT PAPEL TO USER MARIA;
                """);
        Selection selection = new Selection(Set.of(), Set.of(), Set.of("A"), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of("PAPEL"), Set.of(), Set.of(), Set.of());
        String script = extract(source, ExtractionOptions.DEFAULT, selection);
        assertTrue(script.contains("GRANT SELECT ON A TO ROLE PAPEL;"), script);
        assertTrue(script.contains("GRANT PAPEL TO USER MARIA;"), script);
        assertFalse(script.contains("ON B ") || script.contains("TABLE B"), script);
        ExportTestDatabase empty = ExportTestDatabase.create("grants-restricted-copy", "UTF8");
        empty.run(script);
        assertEquals(script, extract(empty, ExtractionOptions.DEFAULT));
    }

    @Test
    void decodeDomainsReplacesTheDomainByItsTypeInColumns() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("grants-decode");
        List<String> warnings = new ArrayList<>();
        String plain = extract(source, ExtractionOptions.DEFAULT);
        String decoded = extract(source, ExtractionOptions.DEFAULT.withDecodeDomains(true), Selection.all(),
                warnings);
        assertTrue(tableDefinition(plain, "CLIENTE").contains("D_NOME"), plain);
        String table = tableDefinition(decoded, "CLIENTE");
        assertFalse(table.matches("(?s).*\\bD_[A-Z_]+\\b.*"), table);
        assertTrue(table.contains("VARCHAR("), table);
        assertTrue(decoded.contains("CREATE DOMAIN D_NOME AS"), "os domínios continuam no script");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Decode domains") && w.contains("D_DINHEIRO")),
                warnings.toString());
        // O script decodificado roda do mesmo jeito num banco vazio.
        ExportTestDatabase empty = ExportTestDatabase.create("grants-decode-copy", "UTF8");
        empty.run(decoded);
    }

    @Test
    void decodeDomainsKeepsTheDefaultAndNotNullOfTheDomain() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("grants-decode-defaults", "UTF8");
        source.run("CREATE DOMAIN D_SIM_NAO AS CHAR(1) DEFAULT 'S' NOT NULL;\n"
                + "CREATE TABLE T (A D_SIM_NAO, B D_SIM_NAO DEFAULT 'N', C D_SIM_NAO);");
        String decoded = extract(source, ExtractionOptions.DEFAULT.withDecodeDomains(true));
        String table = tableDefinition(decoded, "T");
        assertTrue(table.contains("A CHAR(1) DEFAULT 'S' NOT NULL"), table);
        assertTrue(table.contains("B CHAR(1) DEFAULT 'N' NOT NULL"), table);
        assertFalse(table.contains("D_SIM_NAO"), table);
    }

    @Test
    void decodeDomainsAlsoDecodesRoutineParametersButNotTypeOf() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.create("grants-decode-routines", "UTF8");
        source.run("CREATE DOMAIN D_VALOR AS NUMERIC(15,2);\nSET TERM ^ ;\n"
                + "CREATE PROCEDURE SP_P (X D_VALOR, Y TYPE OF D_VALOR) RETURNS (Z D_VALOR) AS BEGIN Z = X + Y;"
                + " SUSPEND; END^\nSET TERM ; ^");
        String plain = extract(source, ExtractionOptions.DEFAULT);
        assertTrue(plain.contains("X D_VALOR"), plain);
        String decoded = extract(source, ExtractionOptions.DEFAULT.withDecodeDomains(true));
        assertTrue(decoded.contains("X NUMERIC(15,2)"), decoded);
        assertTrue(decoded.contains("Z NUMERIC(15,2)"), decoded);
        assertTrue(decoded.contains("Y TYPE OF D_VALOR"), decoded);
    }

    /** Cada opção do script ligada e desligada no mesmo banco, com o efeito que ela promete. */
    @Test
    void everyOtherOptionTurnsItsEffectOnAndOff() throws Exception {
        ExportTestDatabase source = ExportTestDatabase.syntheticDatabase("grants-options");
        ExtractionOptions off = ExtractionOptions.DEFAULT;
        // createDatabase, alwaysCharacterSet, computedBySeparate, alwaysQuote, createOrAlter, noSetTerm, omitHeader, useSequence
        String createDatabase = extract(source, new ExtractionOptions(true, false, false, false, false, false, false, false));
        assertTrue(createDatabase.contains("CREATE DATABASE '"), createDatabase);
        assertFalse(synthetic.contains("CREATE DATABASE"), synthetic);

        String charset = extract(source, new ExtractionOptions(false, true, false, false, false, false, false, false));
        assertTrue(charset.contains("VARCHAR(60) CHARACTER SET UTF8") && !synthetic.contains("VARCHAR(60) CHARACTER SET UTF8"),
                "CHARACTER SET em toda coluna de texto");

        String computed = extract(source, new ExtractionOptions(false, false, true, false, false, false, false, false));
        assertTrue(computed.matches("(?s).*ALTER TABLE \\w+ ADD \\w+ COMPUTED BY.*"), computed);
        assertFalse(synthetic.matches("(?s).*ALTER TABLE \\w+ ADD \\w+ COMPUTED BY.*"), synthetic);

        String quoted = extract(source, new ExtractionOptions(false, false, false, true, false, false, false, false));
        assertTrue(quoted.contains("CREATE DOMAIN \"D_ID\" AS"), quoted);
        assertTrue(synthetic.contains("CREATE DOMAIN D_ID AS"), synthetic);

        String orAlter = extract(source, new ExtractionOptions(false, false, false, false, true, false, false, false));
        assertTrue(orAlter.contains("CREATE OR ALTER VIEW") && !synthetic.contains("CREATE OR ALTER"), orAlter);

        String noSetTerm = extract(source, new ExtractionOptions(false, false, false, false, false, true, false, false));
        assertFalse(noSetTerm.contains("SET TERM"), noSetTerm);
        assertTrue(synthetic.contains("SET TERM ^ ;"), synthetic);

        String noHeader = extract(source, new ExtractionOptions(false, false, false, false, false, false, true, false));
        assertFalse(noHeader.contains("SET NAMES") || noHeader.contains("SET SQL DIALECT"), noHeader);
        assertTrue(synthetic.startsWith("SET SQL DIALECT 3;"), synthetic);

        String sequence = extract(source, new ExtractionOptions(false, false, false, false, false, false, false, true));
        assertTrue(sequence.contains("CREATE SEQUENCE GEN_PEDIDO;") && synthetic.contains("CREATE GENERATOR GEN_PEDIDO;"),
                sequence);
        assertEquals(synthetic, extract(source, off));
    }

    /** O {@code CREATE TABLE} da tabela, do começo ao {@code );}. */
    private static String tableDefinition(String script, String table) {
        int start = script.indexOf("CREATE TABLE " + table + " (");
        assertTrue(start >= 0, "sem a tabela " + table + "\n" + script);
        return script.substring(start, script.indexOf("\n);", start));
    }

    private static String extract(ExportTestDatabase database, ExtractionOptions options)
            throws SQLException, IOException {
        return extract(database, options, Selection.all());
    }

    private static String extract(ExportTestDatabase database, ExtractionOptions options, Selection selection)
            throws SQLException, IOException {
        return extract(database, options, selection, new ArrayList<>());
    }

    private static String extract(ExportTestDatabase database, ExtractionOptions options, Selection selection,
            List<String> warnings) throws SQLException, IOException {
        try (Connection connection = database.connect()) {
            StringWriter out = new StringWriter();
            new MetadataExtractor(connection).extract(selection, options, out, null, new ExtractionProgress() {
                @Override
                public void warn(String message) {
                    warnings.add(message);
                }
            });
            return out.toString();
        }
    }
}
