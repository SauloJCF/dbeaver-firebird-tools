package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * Parâmetro de entrada ou de saída.
 *
 * @param type texto do tipo declarado, como aparece na Fonte (ex.: {@code NUMERIC(18,4)}, {@code TYPE OF D_VALOR})
 * @param defaultValue valor padrão de um parâmetro de entrada, ou {@code null}
 */
public record ParameterDeclaration(
        String name, String type, boolean notNull, Expression defaultValue, SourcePosition position) {
}
