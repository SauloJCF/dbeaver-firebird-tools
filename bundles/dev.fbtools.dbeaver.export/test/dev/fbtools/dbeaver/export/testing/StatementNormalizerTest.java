package dev.fbtools.dbeaver.export.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class StatementNormalizerTest {

    @Test
    void ignoresCaseSpacingLineBreaksAndComments() {
        assertEquals(StatementNormalizer.normalize("CREATE DOMAIN D_ID AS INTEGER NOT NULL"),
                StatementNormalizer.normalize("/* x */ create  domain d_id\n as -- y\n integer\tnot null"));
    }

    @Test
    void spacingAroundPunctuationDoesNotMatter() {
        assertEquals(StatementNormalizer.normalize("CHECK (VALUE IN ('A','B'))"),
                StatementNormalizer.normalize("CHECK(VALUE IN ( 'A' , 'B' ))"));
    }

    @Test
    void quotedPlainIdentifiersLoseTheirQuotes() {
        assertEquals(StatementNormalizer.normalize("CREATE TABLE LOG (ID INTEGER)"),
                StatementNormalizer.normalize("CREATE TABLE \"LOG\" (ID INTEGER)"));
    }

    @Test
    void quotedIdentifiersThatNeedQuotesKeepThem() {
        assertNotEquals(StatementNormalizer.normalize("CREATE TABLE \"Log\" (ID INTEGER)"),
                StatementNormalizer.normalize("CREATE TABLE LOG (ID INTEGER)"));
    }

    @Test
    void literalsAreCaseAndSpaceSensitive() {
        assertNotEquals(StatementNormalizer.normalize("DEFAULT 'ativo'"), StatementNormalizer.normalize("DEFAULT 'ATIVO'"));
        assertNotEquals(StatementNormalizer.normalize("DEFAULT 'a b'"), StatementNormalizer.normalize("DEFAULT 'a  b'"));
    }

    @Test
    void multiCharacterOperatorsStayTogether() {
        assertEquals("A || B <> C", StatementNormalizer.normalize("a||b<>c"));
    }
}
