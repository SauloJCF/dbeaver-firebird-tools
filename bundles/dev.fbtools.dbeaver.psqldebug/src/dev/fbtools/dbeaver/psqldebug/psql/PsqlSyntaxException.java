package dev.fbtools.dbeaver.psqldebug.psql;

import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;

/**
 * Texto que a gramática da camada procedural não reconhece, ou, num watch, que o parser recusa (ver
 * {@link RoutineParser#parseWatch}).
 */
public final class PsqlSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SourcePosition position;
    private final String detail;

    PsqlSyntaxException(String detail, SourcePosition position) {
        super(position + ": " + detail);
        this.position = position;
        this.detail = detail;
    }

    /** Onde o reconhecimento falhou. */
    public SourcePosition position() {
        return position;
    }

    /** O que não foi reconhecido, sem a posição. */
    public String detail() {
        return detail;
    }
}
