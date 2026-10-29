package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code CONTINUE [label];}: vai para a próxima iteração do laço.
 *
 * @param label nome normalizado do laço que continua, ou {@code null} para o laço mais interno
 */
public record Continue(String label, SourcePosition position) implements Statement {
}
