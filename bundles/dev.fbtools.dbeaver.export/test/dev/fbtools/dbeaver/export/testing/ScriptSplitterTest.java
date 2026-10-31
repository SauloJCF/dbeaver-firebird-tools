package dev.fbtools.dbeaver.export.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScriptSplitterTest {

    @Test
    void splitsOnSemicolons() {
        assertEquals(List.of("CREATE DOMAIN D AS INTEGER", "CREATE GENERATOR G"),
                ScriptSplitter.split("CREATE DOMAIN D AS INTEGER;\nCREATE GENERATOR G;\n"));
    }

    @Test
    void setTermChangesTheTerminatorAndIsNotEmitted() {
        String script = "SET TERM ^ ;\nCREATE PROCEDURE P AS BEGIN EXIT; END^\nSET TERM ; ^\nCREATE GENERATOR G;";
        assertEquals(List.of("CREATE PROCEDURE P AS BEGIN EXIT; END", "CREATE GENERATOR G"),
                ScriptSplitter.split(script));
    }

    @Test
    void terminatorsInsideLiteralsQuotedIdentifiersAndCommentsDoNotSplit() {
        String script = "COMMENT ON TABLE T IS 'a; b ''c;''';\n"
                + "CREATE TABLE \"A;B\" (X INTEGER); /* nada; aqui */ -- nem; aqui\n"
                + "CREATE GENERATOR G;";
        assertEquals(List.of(
                "COMMENT ON TABLE T IS 'a; b ''c;'''",
                "CREATE TABLE \"A;B\" (X INTEGER)",
                "/* nada; aqui */ -- nem; aqui\nCREATE GENERATOR G"),
                ScriptSplitter.split(script));
    }

    @Test
    void setTermPrecededByCommentsStillChangesTheTerminator() {
        String script = "/* banner */\n-- outro\nSET TERM ^ ;\nCREATE TRIGGER T AS BEGIN EXIT; END^\nSET TERM ; ^";
        assertEquals(List.of("CREATE TRIGGER T AS BEGIN EXIT; END"), ScriptSplitter.split(script));
    }

    @Test
    void statementWithoutFinalTerminatorIsKept() {
        assertEquals(List.of("COMMIT"), ScriptSplitter.split("COMMIT"));
    }

    @Test
    void multiCharacterTerminator() {
        assertEquals(List.of("A", "B"), ScriptSplitter.split("SET TERM !! ;\nA!!\nB!!\n"));
    }
}
