package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code [label:] FOR EXECUTE STATEMENT ... INTO ... DO statement}: laço sobre o resultado de um statement
 * dinâmico local.
 *
 * @param label nome normalizado do label, ou {@code null}
 * @param position início do statement, que é o label quando houver
 */
public record ForExecuteStatement(String label, ExecuteStatement statement, Statement body,
        SourcePosition position) implements Statement {
}
