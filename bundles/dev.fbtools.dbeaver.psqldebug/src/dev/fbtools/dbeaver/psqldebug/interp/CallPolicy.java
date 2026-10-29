package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;
import java.util.Map;
import java.util.NavigableSet;

import dev.fbtools.dbeaver.psqldebug.psql.CallSites;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;

/**
 * Escolha, a cada chamada Step into elegível, entre interpretar a Rotina chamada, acompanhada pela camada de debug
 * (step into), ou executar a chamada no servidor (step over). Quem controla a Sessão de debug decide; o Interpretador
 * só pergunta. No step over, a chamada também é interpretada e acompanhada quando a camada de debug pode parar dentro
 * dela ({@link #stopsInside}: um breakpoint numa Rotina que ela alcança).
 * <p>
 * Exceção: no {@code FOR SELECT … FROM sp(...)}, o step over também interpreta a procedure, sem que a camada de
 * debug pare nela ({@link Frame#steppedInto()} falso). Um cursor do servidor não é fiel ao motor: o servidor remoto
 * busca linhas à frente do laço, rodando a procedure além do ponto em que o laço sai (com o DML dela), e num erro
 * no meio do lote o Jaybird descarta as linhas boas que vieram junto. O motor, em PSQL, busca uma linha por vez. Só
 * uma procedure que não dá para interpretar vai ao cursor do servidor. O mesmo vale para uma rotina privada da
 * package de quem chama: fora da package ela não existe, e o servidor não a acharia. E para uma sub-rotina que usa
 * variáveis da Rotina que a declara: a cópia dela no Package temporário de debug não as teria. As outras sub-rotinas
 * vão ao servidor por essa cópia.
 */
@FunctionalInterface
public interface CallPolicy {

    /** Toda chamada executada no servidor. */
    CallPolicy SERVER = (call, caller) -> false;

    /** Toda chamada elegível interpretada. */
    CallPolicy INTERPRET = (call, caller) -> true;

    /**
     * Chamado antes de executar a chamada, com o frame de quem chama no topo de {@link Interpreter#frames()}. Se o
     * nome não for de uma Rotina PSQL com Fonte do banco ({@code UPPER(...)}, uma tabela, uma procedure externa) ou
     * se a Rotina não passar no Pré-check, a chamada vai para o servidor mesmo com {@code true}. Também é chamado a
     * cada volta seguinte de um {@code FOR SELECT} sobre procedure interpretada que a camada de debug não acompanha
     * (ver {@link Frame#steppedInto()}), antes de a procedure continuar.
     *
     * @return {@code true} para interpretar a Rotina chamada, acompanhada pela camada de debug
     */
    boolean stepInto(CallSites.CallSite call, Frame caller);

    /**
     * Com {@link #stepInto} falso: interpretar a Rotina chamada mesmo assim, acompanhada pela camada de debug, porque
     * a execução pode parar dentro dela. O step over continua sem parar nela, fora desses pontos.
     *
     * @param reach o que a chamada pode executar, se interpretada; calculado só quando pedido
     */
    default boolean stopsInside(Reach reach) throws SQLException {
        return false;
    }

    /**
     * Linhas com statement que uma chamada interpretada pode executar: as da Rotina chamada (com as das sub-rotinas
     * dela) e, seguindo as chamadas Step into elegíveis que dá para interpretar, as das Rotinas que ela alcança. Na
     * primeira vez, carrega a Fonte do banco dessas Rotinas; depois, vale o que já foi lido na sessão.
     */
    @FunctionalInterface
    interface Reach {

        /**
         * @return as linhas, a partir de 1, por Rotina de cujo texto elas são ({@link Frame#sourceRoutine()}); vazio
         *         se a chamada iria inteira ao servidor
         */
        Map<RoutineDefinition, NavigableSet<Integer>> lines() throws SQLException;
    }
}
