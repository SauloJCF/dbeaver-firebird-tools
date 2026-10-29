package dev.fbtools.dbeaver.psqldebug.psql.ast;

/** {@code RETURN valor;}: termina a function com o valor convertido para o tipo de retorno. */
public record Return(Expression value, SourcePosition position) implements Statement {
}
