package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * {@code FETCH [<orientação> FROM] cursor [INTO ...];}
 *
 * @param offset o {@code n} de {@code ABSOLUTE n} e {@code RELATIVE n}; {@code null} nas outras orientações
 * @param into nomes normalizados dos alvos, na ordem; vazio se não houver (os campos são lidos como
 *            {@code cursor.campo})
 */
public record Fetch(String cursor, Orientation orientation, Expression offset, List<String> into,
        SourcePosition position) implements Statement {

    /** Para onde o {@code FETCH} move o cursor. Só {@code NEXT} vale para cursor sem {@code SCROLL}. */
    public enum Orientation {
        NEXT, PRIOR, FIRST, LAST, ABSOLUTE, RELATIVE
    }

    public Fetch {
        into = List.copyOf(into);
    }
}
