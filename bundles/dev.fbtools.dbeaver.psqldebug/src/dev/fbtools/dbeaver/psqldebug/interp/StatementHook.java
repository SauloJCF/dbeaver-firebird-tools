package dev.fbtools.dbeaver.psqldebug.interp;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Statement;

/**
 * Chamado pelo Interpretador antes de cada statement, na thread da execução, com o frame já na posição dele. Num
 * laço ({@code WHILE}, {@code FOR SELECT}, {@code FOR EXECUTE STATEMENT}), é chamado de novo com o laço antes de cada
 * volta seguinte à primeira, antes de avaliar a condição ou de buscar a linha seguinte. É onde quem controla a Sessão
 * de debug pausa a execução ou a interrompe: o que o gancho lançar sobe sem passar por nenhum {@code WHEN} da
 * Rotina, que só trata {@link java.sql.SQLException}.
 */
@FunctionalInterface
public interface StatementHook {

    /** Não faz nada. */
    StatementHook NONE = (frame, statement) -> {
    };

    /** @param frame frame da Rotina em execução, o de cima de {@link Interpreter#frames()} */
    void beforeStatement(Frame frame, Statement statement);
}
