package dev.fbtools.dbeaver.export.extract;

import java.util.Set;

/**
 * Os objetos a extrair, por nome; {@code null} num tipo significa todos os do banco. Índices são só os do usuário:
 * os das constraints ({@code PRIMARY KEY}, {@code UNIQUE}, {@code FOREIGN KEY}) saem com a tabela.
 */
public record Selection(Set<String> domains, Set<String> generators, Set<String> tables, Set<String> indexes,
        Set<String> views, Set<String> procedures, Set<String> functions, Set<String> packages) {

    /** Só domínios e generators; sem tabelas, índices nem views. */
    public Selection(Set<String> domains, Set<String> generators) {
        this(domains, generators, Set.of(), Set.of(), Set.of());
    }

    /** Sem views. */
    public Selection(Set<String> domains, Set<String> generators, Set<String> tables, Set<String> indexes) {
        this(domains, generators, tables, indexes, Set.of());
    }

    /** Sem procedures, functions nem packages. */
    public Selection(Set<String> domains, Set<String> generators, Set<String> tables, Set<String> indexes,
            Set<String> views) {
        this(domains, generators, tables, indexes, views, Set.of(), Set.of(), Set.of());
    }

    public static Selection all() {
        return new Selection(null, null, null, null, null, null, null, null);
    }

    boolean includesProcedure(String name) {
        return procedures == null || procedures.contains(name);
    }

    boolean includesFunction(String name) {
        return functions == null || functions.contains(name);
    }

    boolean includesPackage(String name) {
        return packages == null || packages.contains(name);
    }

    boolean includesView(String name) {
        return views == null || views.contains(name);
    }

    boolean includesDomain(String name) {
        return domains == null || domains.contains(name);
    }

    boolean includesGenerator(String name) {
        return generators == null || generators.contains(name);
    }

    boolean includesTable(String name) {
        return tables == null || tables.contains(name);
    }

    boolean includesIndex(String name) {
        return indexes == null || indexes.contains(name);
    }
}
