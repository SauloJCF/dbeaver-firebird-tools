package dev.fbtools.dbeaver.psqldebug.psql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fbtools.dbeaver.psqldebug.psql.CallSites.CallSite;
import dev.fbtools.dbeaver.psqldebug.psql.CallSites.Form;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;

/** Classificação de cada chamada como Step into elegível ou Executado no servidor. */
class CallSitesTest {

    @Test
    void eachCallIsClassifiedByWhereItIs() {
        List<CallSite> sites = CallSites.of(RoutineParser.parse("""
                CREATE PROCEDURE P (A INTEGER = F_PADRAO(1)) RETURNS (R INTEGER)
                AS
                DECLARE X INTEGER = F_INICIAL(2);
                DECLARE C CURSOR FOR (SELECT V FROM SP_CURSOR(1));
                BEGIN
                  EXECUTE PROCEDURE SP_A (F_ARG(:A)) RETURNING_VALUES :X;
                  X = F_INTEIRA(X, 1);
                  X = F_MAIOR(X) + 1;
                  IF (F_COND(X) > 0) THEN X = PKG.F_PKG(1);
                  FOR SELECT V FROM SP_FONTE(:X) INTO :R DO SUSPEND;
                  FOR SELECT V FROM SP_FILTRO(:X) WHERE V > 0 INTO :R DO SUSPEND;
                  INSERT INTO T (ID) SELECT F_DML(:X) FROM RDB$DATABASE;
                  UPDATE T SET ID = 0 WHERE ID IN (SELECT V FROM SP_SUB);
                  RETURN_VALUE: WHILE (1 = 0) DO EXCEPTION E MENSAGEM(:X);
                  WHEN ANY DO EXECUTE PROCEDURE SP_HANDLER;
                END"""));

        assertEquals(List.of(
                site(Form.EXPRESSION, "F_PADRAO", 1, 33),
                site(Form.EXPRESSION, "F_INICIAL", 3, 21),
                site(Form.DML, "SP_CURSOR", 4, 37),
                site(Form.EXECUTE_PROCEDURE, "SP_A", 6, 3),
                site(Form.EXPRESSION, "F_ARG", 6, 27),
                site(Form.ASSIGNMENT, "F_INTEIRA", 7, 7),
                site(Form.EXPRESSION, "F_MAIOR", 8, 7),
                site(Form.EXPRESSION, "F_COND", 9, 7),
                new CallSite(Form.ASSIGNMENT, "PKG", "F_PKG", new SourcePosition(9, 31)),
                site(Form.FOR_SELECT, "SP_FONTE", 10, 21),
                site(Form.DML, "SP_FILTRO", 11, 21),
                site(Form.DML, "F_DML", 12, 29),
                site(Form.DML, "SP_SUB", 13, 50),
                site(Form.EXPRESSION, "MENSAGEM", 14, 46),
                site(Form.EXECUTE_PROCEDURE, "SP_HANDLER", 15, 15)), sites);
    }

    @Test
    void onlyTheCallThatIsTheWholeStatementIsEligible() {
        List<CallSite> sites = CallSites.of(RoutineParser.parse("""
                CREATE PROCEDURE P RETURNS (R INTEGER)
                AS
                BEGIN
                  R = F(G(1));
                  FOR SELECT V FROM SP(F(2)) INTO :R DO SUSPEND;
                END"""));

        assertEquals(List.of(true, false, true, false), sites.stream().map(CallSite::stepIntoEligible).toList());
        assertEquals(List.of("F", "G", "SP", "F"), sites.stream().map(CallSite::name).toList());
    }

    private static CallSite site(Form form, String name, int line, int column) {
        return new CallSite(form, null, name, new SourcePosition(line, column));
    }
}
