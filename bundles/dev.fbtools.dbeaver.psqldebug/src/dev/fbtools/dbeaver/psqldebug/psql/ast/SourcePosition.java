package dev.fbtools.dbeaver.psqldebug.psql.ast;

/** Posição no texto da Rotina: linha e coluna, ambas a partir de 1. */
public record SourcePosition(int line, int column) {

    @Override
    public String toString() {
        return "linha " + line + ", coluna " + column;
    }
}
