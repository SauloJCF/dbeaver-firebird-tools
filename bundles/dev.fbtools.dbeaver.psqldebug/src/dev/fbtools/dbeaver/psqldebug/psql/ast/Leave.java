package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code LEAVE [label];} ou {@code BREAK;}: sai do laço.
 *
 * @param label nome normalizado do laço de que sai, ou {@code null} para o laço mais interno
 */
public record Leave(String label, SourcePosition position) implements Statement {
}
