package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code DECLARE [VARIABLE] nome tipo [NOT NULL] [= | DEFAULT valor];}
 *
 * @param type texto do tipo declarado, como aparece na Fonte
 * @param initialValue valor inicial, ou {@code null}
 */
public record VariableDeclaration(
        String name, String type, boolean notNull, Expression initialValue, SourcePosition position) {
}
