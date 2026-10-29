package dev.fbtools.dbeaver.psqldebug.psql.ast;

/** {@code EXIT;}: termina a Rotina. */
public record Exit(SourcePosition position) implements Statement {
}
