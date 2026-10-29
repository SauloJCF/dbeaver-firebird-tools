package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.ast.DmlStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ExecuteStatement;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;

/** Fatias DML e {@code EXECUTE STATEMENT} no parser (issue 04). */
class DmlParserTest {

    @Test
    void colonInsideLiteralOrCommentIsNotAParameter() {
        DmlStatement select = (DmlStatement) only("""
                SELECT ':ID' || /* :NOME */ NOME -- :VALOR
                  FROM T WHERE ID = :ID AND X = 'a:b' INTO :NOME;""");

        assertEquals(List.of("ID"), names(select.sql()));
        assertEquals("""
                SELECT ':ID' || /* :NOME */ NOME -- :VALOR
                  FROM T WHERE ID = ? AND X = 'a:b'\s""", withPlaceholders(select.sql()));
    }

    @Test
    void selectIntoTargetsAreRemovedFromTheSlice() {
        DmlStatement select = (DmlStatement) only("SELECT A, B FROM T WHERE ID = :ID INTO :X, Y;");

        assertEquals(DmlStatement.Kind.SELECT, select.kind());
        assertEquals(List.of("X", "Y"), select.into());
        assertEquals("SELECT A, B FROM T WHERE ID = ? ", withPlaceholders(select.sql()));
    }

    @Test
    void bareNamesInDmlAreColumnsNotVariables() {
        DmlStatement update = (DmlStatement) only("UPDATE T SET A = B + :B WHERE C = \"c\";");

        assertEquals(DmlStatement.Kind.UPDATE, update.kind());
        assertEquals(List.of("B"), names(update.sql()));
        assertEquals(List.of(), update.into());
    }

    @Test
    void tableIntoIsNotAVariableTarget() {
        DmlStatement insert = (DmlStatement) only(
                "INSERT INTO T (A) SELECT A FROM U WHERE U.ID = :ID RETURNING A, OLD.B INTO :R1, :R2;");
        DmlStatement updateOrInsert =
                (DmlStatement) only("UPDATE OR INSERT INTO T (ID) VALUES (:ID) RETURNING ID INTO :R;");
        DmlStatement merge = (DmlStatement) only("""
                MERGE INTO T USING (SELECT :ID ID FROM RDB$DATABASE) S ON T.ID = S.ID
                WHEN NOT MATCHED THEN INSERT (ID) VALUES (S.ID);""");

        assertEquals(DmlStatement.Kind.INSERT, insert.kind());
        assertEquals(List.of("R1", "R2"), insert.into());
        assertTrue(withPlaceholders(insert.sql()).endsWith("RETURNING A, OLD.B "));
        assertEquals(DmlStatement.Kind.UPDATE_OR_INSERT, updateOrInsert.kind());
        assertEquals(List.of("R"), updateOrInsert.into());
        assertEquals(DmlStatement.Kind.MERGE, merge.kind());
        assertEquals(List.of(), merge.into());
        assertEquals(List.of("ID"), names(merge.sql()));
    }

    @Test
    void cteIsASelect() {
        DmlStatement select = (DmlStatement) only("WITH C AS (SELECT 1 X FROM RDB$DATABASE) SELECT X FROM C INTO :X;");

        assertEquals(DmlStatement.Kind.SELECT, select.kind());
        assertEquals(List.of("X"), select.into());
    }

    @Test
    void dmlPositionIsItsFirstToken() {
        List<Statement> statements = statements("""
                  X = 1;
                  DELETE FROM T
                    WHERE ID = :X;""");

        assertEquals(new SourcePosition(8, 1), statements.get(1).position());
    }

    @Test
    void executeStatementWithPositionalParametersAndInto() {
        ExecuteStatement execute = (ExecuteStatement) only(
                "EXECUTE STATEMENT ('SELECT A FROM T WHERE ID = ?') (:ID, X + 1) INTO :A;");

        assertEquals("'SELECT A FROM T WHERE ID = ?'", execute.sql().text());
        assertEquals(2, execute.parameters().size());
        assertNull(execute.parameters().get(0).name());
        assertEquals(List.of("ID"), names(execute.parameters().get(0).value()));
        assertEquals(List.of("X"), names(execute.parameters().get(1).value()));
        assertEquals(List.of("A"), execute.into());
        assertEquals(List.of(), execute.unsupported());
    }

    @Test
    void executeStatementWithNamedParametersAndOptions() {
        ExecuteStatement execute = (ExecuteStatement) only("""
                EXECUTE STATEMENT (V_SQL) (P1 := :A, EXCESS "p2" := 'x')
                  WITH COMMON TRANSACTION WITH CALLER PRIVILEGES INTO :R;""");

        assertEquals(List.of("V_SQL"), names(execute.sql()));
        assertEquals("P1", execute.parameters().get(0).name());
        assertEquals("p2", execute.parameters().get(1).name());
        assertTrue(execute.parameters().get(1).excess());
        assertEquals(List.of("R"), execute.into());
        assertEquals(List.of(), execute.unsupported());
    }

    @Test
    void executeStatementWithoutParentheses() {
        ExecuteStatement execute = (ExecuteStatement) only("EXECUTE STATEMENT 'UPDATE T SET A = 1' || V;");

        assertEquals("'UPDATE T SET A = 1' || V", execute.sql().text());
        assertEquals(List.of(), execute.into());
    }

    @Test
    void executeStatementUnknownOptionIsASyntaxError() {
        PsqlSyntaxException error = assertThrows(PsqlSyntaxException.class,
                () -> only("EXECUTE STATEMENT ('X') (1) FOO;"));

        assertEquals(new SourcePosition(7, 31), error.position());
    }

    @Test
    void namedParametersOfDynamicSqlBecomePositional() {
        DynamicSql.Positional positional =
                DynamicSql.withPositionalParameters("SELECT ':A' FROM T WHERE A = :a AND B = :\"b\" -- :c");

        assertEquals("SELECT ':A' FROM T WHERE A = ? AND B = ? -- :c", positional.sql());
        assertEquals(List.of("A", "b"), positional.names());
    }

    private static Statement only(String statement) {
        List<Statement> statements = statements("  " + statement);
        assertEquals(1, statements.size());
        return statements.get(0);
    }

    private static List<Statement> statements(String body) {
        return RoutineParser.parse("""
                CREATE PROCEDURE P (ID INTEGER)
                RETURNS (A INTEGER)
                AS
                DECLARE X INTEGER;
                DECLARE Y INTEGER;
                BEGIN
                %s
                END""".formatted(body)).body().statements();
    }

    private static List<String> names(Expression expression) {
        return expression.parts().stream()
                .filter(part -> part instanceof Expression.Name)
                .map(part -> ((Expression.Name) part).name())
                .toList();
    }

    /** Texto como iria ao servidor se todos os candidatos fossem variáveis. */
    private static String withPlaceholders(Expression expression) {
        StringBuilder text = new StringBuilder();
        for (Expression.Part part : expression.parts()) {
            text.append(part instanceof Expression.Name ? "?" : part.sql());
        }
        return text.toString();
    }
}
