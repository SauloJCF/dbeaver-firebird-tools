package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;

/** Expressão de um watch (issue 16). */
class WatchParserTest {

    @Test
    void namesAreVariableCandidates() {
        Expression watch = RoutineParser.parseWatch("a * :b + 1");

        assertEquals(List.of(new Expression.Name("A", "a"), new Expression.Sql(" * "), new Expression.Name("B", ":b"),
                new Expression.Sql(" + 1")), watch.parts());
    }

    @Test
    void trailingCommentIsLeftOut() {
        // No SELECT (<expr>) FROM RDB$DATABASE, o comentário de linha engoliria o parêntese.
        assertEquals("A + 1", RoutineParser.parseWatch(" A + 1 -- comentário").text());
    }

    @Test
    void textMustBeASingleExpression() {
        for (String text : List.of("A; DELETE FROM T", "A, B", "(A", "A)",
                "A) FROM RDB$DATABASE UNION ALL SELECT (1", "", "  -- só comentário")) {
            assertThrows(PsqlSyntaxException.class, () -> RoutineParser.parseWatch(text), text);
        }
        // Dentro de parênteses e de literais, ';', ',' e ')' fazem parte da expressão.
        RoutineParser.parseWatch("COALESCE(A, ';)') || (SELECT MAX(X) FROM T WHERE Y IN (1, 2))");
    }

    @Test
    void functionsThatChangeTheDatabaseAreRefused() {
        PsqlSyntaxException generator = assertThrows(PsqlSyntaxException.class,
                () -> RoutineParser.parseWatch("A + gen_id(G, 1)"));
        assertTrue(generator.detail().startsWith("GEN_ID não é permitido num watch"), generator.detail());
        assertEquals(new SourcePosition(1, 5), generator.position());

        PsqlSyntaxException sequence = assertThrows(PsqlSyntaxException.class,
                () -> RoutineParser.parseWatch("NEXT VALUE FOR G"));
        assertTrue(sequence.detail().startsWith("NEXT VALUE FOR não é permitido"), sequence.detail());
        assertThrows(PsqlSyntaxException.class,
                () -> RoutineParser.parseWatch("RDB$SET_CONTEXT('USER_SESSION', 'X', 1)"));

        assertThrows(PsqlSyntaxException.class, () -> RoutineParser.parseWatch("GEN_ID(G, 0 + 1)"));

        // Ler o gerador ou o contexto não altera nada; nem uma variável chamada NEXT.
        RoutineParser.parseWatch("GEN_ID( G , 0 ) + 1");
        RoutineParser.parseWatch("RDB$GET_CONTEXT('SYSTEM', 'ENGINE_VERSION')");
        RoutineParser.parseWatch("NEXT + 1");
    }
}
