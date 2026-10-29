package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import dev.fbtools.dbeaver.psqldebug.psql.CallSites;
import dev.fbtools.dbeaver.psqldebug.psql.PreCheck;
import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.StatementLines;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.Subroutine;

/**
 * Rotinas que uma chamada Step into elegível alcança quando interpretada: a Rotina chamada que o Interpretador executa
 * ({@link #interpretable}) e as linhas que a chamada pode executar ({@link #reach}, ver {@link CallPolicy.Reach}).
 * Resolve os nomes como o {@link Interpreter}: um nome sem package é primeiro o de uma sub-rotina; depois vem o
 * catálogo. Guarda o que calculou: as Rotinas do catálogo e as sub-rotinas são as mesmas durante toda a sessão.
 */
final class CallTargets {

    private final RoutineCatalog catalog;
    private final Map<RoutineDefinition, Boolean> checked = new IdentityHashMap<>();
    private final Map<RoutineDefinition, Map<RoutineDefinition, NavigableSet<Integer>>> reaches =
            new IdentityHashMap<>();

    CallTargets(RoutineCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Rotina chamada que o Interpretador executa: PSQL, com Fonte do banco reconhecida pela gramática e sem
     * construção recusada pelo Pré-check.
     *
     * @param callerPackage package de quem chama, ou {@code null} (numa sub-rotina, nenhuma, como no motor)
     * @return {@code null} se a chamada vai inteira ao servidor
     */
    RoutineDefinition interpretable(RoutineDefinition.Kind kind, String callerPackage, String packageName,
            String name) throws SQLException {
        RoutineDefinition routine;
        try {
            routine = catalog.find(kind, callerPackage, packageName, name).orElse(null);
        } catch (PsqlSyntaxException e) {
            return null;
        }
        if (routine == null) {
            return null;
        }
        return checked.computeIfAbsent(routine, found -> PreCheck.scan(found).isEmpty()) ? routine : null;
    }

    /**
     * Linhas que a chamada pode executar (ver {@link CallPolicy.Reach}).
     *
     * @param routine a Rotina chamada, que o Interpretador executa
     * @param text Rotina de cujo texto são as posições dela: numa sub-rotina, a que a declara; senão, ela mesma
     */
    Map<RoutineDefinition, NavigableSet<Integer>> reach(RoutineDefinition routine, RoutineDefinition text)
            throws SQLException {
        Map<RoutineDefinition, NavigableSet<Integer>> lines = reaches.get(routine);
        if (lines == null) {
            Map<RoutineDefinition, NavigableSet<Integer>> collected = new IdentityHashMap<>();
            collect(routine, text, collected, Collections.newSetFromMap(new IdentityHashMap<>()));
            lines = Collections.unmodifiableMap(collected);
            reaches.put(routine, lines);
        }
        return lines;
    }

    /**
     * Junta as linhas da Rotina e, pelas chamadas elegíveis dela, as das que ela alcança. Numa Rotina com sub-rotinas,
     * as linhas e as chamadas dos corpos delas entram junto, mesmo que a Rotina não as chame: sobra, nunca falta.
     */
    private void collect(RoutineDefinition routine, RoutineDefinition text,
            Map<RoutineDefinition, NavigableSet<Integer>> lines, Set<RoutineDefinition> visited) throws SQLException {
        if (!visited.add(routine)) {
            return; // recursão, ou a mesma Rotina por outro caminho
        }
        lines.computeIfAbsent(text, source -> new TreeSet<>()).addAll(StatementLines.of(routine));
        for (CallSites.CallSite site : CallSites.of(routine)) {
            if (!site.stepIntoEligible()) {
                continue; // dentro de expressão ou de Fatia DML, a chamada roda no servidor
            }
            RoutineDefinition.Kind kind = site.form() == CallSites.Form.ASSIGNMENT ? RoutineDefinition.Kind.FUNCTION
                    : RoutineDefinition.Kind.PROCEDURE;
            Subroutine subroutine = site.packageName() == null ? text.subroutine(kind, site.name()) : null;
            if (subroutine != null) {
                collect(subroutine.routine(), text, lines, visited);
                continue;
            }
            RoutineDefinition callee = interpretable(kind, routine.packageName(), site.packageName(), site.name());
            if (callee != null) {
                collect(callee, callee, lines, visited);
            }
        }
    }
}
