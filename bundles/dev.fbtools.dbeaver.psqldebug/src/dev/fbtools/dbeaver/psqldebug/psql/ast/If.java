package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code IF (condição) THEN statement [ELSE statement]}.
 *
 * @param elseBranch statement do {@code ELSE}, ou {@code null}
 */
public record If(Expression condition, Statement thenBranch, Statement elseBranch, SourcePosition position)
        implements Statement {
}
