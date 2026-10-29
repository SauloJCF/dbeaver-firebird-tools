package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code alvo = expressão;}
 *
 * @param call a expressão, quando ela é inteira uma chamada {@code F(...)}; senão {@code null}
 */
public record Assignment(String target, Expression value, FunctionCall call, SourcePosition position)
        implements Statement {
}
