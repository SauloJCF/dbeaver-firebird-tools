package dev.fbtools.dbeaver.psqldebug.interp;

/**
 * Valor de um watch numa pausa ({@link Interpreter#watch}).
 *
 * @param value o valor, como o Jaybird o devolve
 * @param type tipo que o servidor dá à expressão, para mostrar ({@code BIGINT}, {@code VARCHAR(12)})
 */
public record WatchValue(Object value, String type) {
}
