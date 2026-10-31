package dev.fbtools.dbeaver.export.extract;

import java.util.Set;

/** Os objetos a extrair, por nome; {@code null} num tipo significa todos os do banco. */
public record Selection(Set<String> domains, Set<String> generators) {

    public static Selection all() {
        return new Selection(null, null);
    }

    boolean includesDomain(String name) {
        return domains == null || domains.contains(name);
    }

    boolean includesGenerator(String name) {
        return generators == null || generators.contains(name);
    }
}
