package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/** {@code WHEN <condição>, ... DO statement}: handler de erro no fim de um {@link Block}. */
public record Handler(List<ErrorCondition> conditions, Statement action, SourcePosition position) {

    public Handler {
        conditions = List.copyOf(conditions);
    }
}
