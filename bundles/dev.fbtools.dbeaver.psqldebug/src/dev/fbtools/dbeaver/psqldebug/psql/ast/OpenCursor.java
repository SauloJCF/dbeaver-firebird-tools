package dev.fbtools.dbeaver.psqldebug.psql.ast;

/** {@code OPEN cursor;} */
public record OpenCursor(String cursor, SourcePosition position) implements Statement {
}
