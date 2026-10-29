package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code DECLARE nome [SCROLL | NO SCROLL] CURSOR FOR (SELECT ...);}
 *
 * @param select o {@code SELECT} do cursor, uma Fatia DML sem {@code INTO}; os {@code :var} valem no
 *            {@code OPEN}
 */
public record CursorDeclaration(String name, boolean scroll, DmlStatement select, SourcePosition position) {
}
