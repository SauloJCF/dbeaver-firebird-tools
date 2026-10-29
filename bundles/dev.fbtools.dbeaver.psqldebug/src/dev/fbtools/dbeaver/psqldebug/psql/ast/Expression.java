package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * Expressão PSQL tratada como texto opaco (Modo Careful, ADR 0002). O texto é quebrado em trechos SQL e em
 * nomes que podem ser variáveis; quem decide se um nome é variável é o escopo, na hora de avaliar.
 *
 * @param text texto original, com comentários e espaços
 * @param parts o mesmo texto, em ordem, separando os candidatos a referência de variável
 */
public record Expression(String text, List<Part> parts, SourcePosition position) {

    public Expression {
        parts = List.copyOf(parts);
    }

    /** Trecho de uma {@link Expression}. */
    public sealed interface Part permits Sql, Name, Field, Call {

        /** Texto original do trecho. */
        String sql();
    }

    /** Texto repassado ao servidor como está. */
    public record Sql(String sql) implements Part {
    }

    /**
     * Identificador solto (não é função, nem parte de nome qualificado), com o {@code :} opcional.
     *
     * @param name nome normalizado: maiúsculo se regular, literal se entre aspas
     */
    public record Name(String name, String sql) implements Part {
    }

    /**
     * Nome qualificado solto ({@code [:]a.b}), que pode ser campo de cursor ({@code cursor.campo}). Numa Fatia DML,
     * só com o {@code :}, porque sem ele {@code a.b} é coluna de tabela.
     *
     * @param qualifier nome normalizado antes do ponto
     * @param name nome normalizado depois do ponto
     */
    public record Field(String qualifier, String name, String sql) implements Part {
    }

    /**
     * Nome que pode ser de uma Rotina chamada: {@code [pkg.]nome(} ou, numa Fatia DML, {@code FROM|JOIN [pkg.]nome}
     * sem parênteses. É só um candidato: pode ser função interna ({@code UPPER(}), tabela ou, depois de
     * {@code FROM}, coluna de {@code EXTRACT(... FROM c)}; quem distingue é o banco. O texto é só o do nome, sem o
     * {@code (}.
     *
     * @param packageName nome normalizado antes do ponto, ou {@code null}
     * @param name nome normalizado
     * @param arguments seguido de {@code (}
     */
    public record Call(String packageName, String name, boolean arguments, String sql, SourcePosition position)
            implements Part {
    }
}
