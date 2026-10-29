package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code [label:] WHILE (condição) DO statement}.
 *
 * @param label nome normalizado do label, ou {@code null}
 * @param position início do statement, que é o label quando houver
 */
public record While(String label, Expression condition, Statement body, SourcePosition position)
        implements Statement {
}
