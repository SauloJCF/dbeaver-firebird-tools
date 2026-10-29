package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

import dev.fbtools.dbeaver.psqldebug.psql.PsqlSyntaxException;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;

/** Rotinas que uma Rotina em execução pode chamar, com a Fonte do banco de cada uma. */
public interface RoutineCatalog {

    /**
     * Rotina chamada, resolvida como o motor resolve: dentro do corpo de uma package, um nome sem package é
     * primeiro uma rotina da própria package.
     *
     * @param kind {@link RoutineDefinition.Kind#PROCEDURE} ou {@link RoutineDefinition.Kind#FUNCTION}
     * @param callerPackage package de quem chama, ou {@code null}
     * @param packageName package escrita na chamada, ou {@code null}
     * @return vazio se o nome não é de uma Rotina PSQL com Fonte do banco (função interna, UDF, tabela, Rotina
     *         externa)
     * @throws PsqlSyntaxException se a Fonte do banco tiver trecho que a gramática não reconhece
     */
    Optional<RoutineDefinition> find(RoutineDefinition.Kind kind, String callerPackage, String packageName,
            String name) throws SQLException;

    /** Nomes normalizados das procedures e functions da package, públicas e privadas. */
    Set<String> packageRoutines(String packageName) throws SQLException;

    /** Nomes normalizados das rotinas privadas da package (só no corpo), que não se alcança de fora dela. */
    Set<String> privateRoutines(String packageName) throws SQLException;
}
