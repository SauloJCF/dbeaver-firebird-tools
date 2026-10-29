package dev.fbtools.dbeaver.psqldebug.psql.ast;

/** {@code CLOSE cursor;} */
public record CloseCursor(String cursor, SourcePosition position) implements Statement {
}
