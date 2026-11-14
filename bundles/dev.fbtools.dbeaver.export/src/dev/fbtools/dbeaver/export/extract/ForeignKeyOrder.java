package dev.fbtools.dbeaver.export.extract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A ordem de FK do Script de dados: a tabela pai (a que a FK referencia) vem antes da filha. A ordem da lista dada
 * só decide os empates. FKs circulares não têm ordem que as satisfaça: a tabela com menos pais ainda pendentes sai
 * primeiro, e a extração avisa quais tabelas estão no ciclo e o que fazer. A FK de uma tabela para ela mesma não conta.
 */
public final class ForeignKeyOrder {

    private static final String FOREIGN_KEYS = """
            SELECT rc.RDB$RELATION_NAME, pk.RDB$RELATION_NAME
              FROM RDB$RELATION_CONSTRAINTS rc
              JOIN RDB$REF_CONSTRAINTS ref ON ref.RDB$CONSTRAINT_NAME = rc.RDB$CONSTRAINT_NAME
              JOIN RDB$RELATION_CONSTRAINTS pk ON pk.RDB$CONSTRAINT_NAME = ref.RDB$CONST_NAME_UQ
             WHERE rc.RDB$CONSTRAINT_TYPE = 'FOREIGN KEY'""";

    private ForeignKeyOrder() {
    }

    /** Tabela filha -> tabelas pai, sem as FKs para a própria tabela. */
    public static Map<String, Set<String>> read(Connection connection) throws SQLException {
        Map<String, Set<String>> parents = new TreeMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(FOREIGN_KEYS)) {
            while (rs.next()) {
                String child = rs.getString(1).strip();
                String parent = rs.getString(2).strip();
                if (!child.equals(parent)) {
                    parents.computeIfAbsent(child, k -> new TreeSet<>()).add(parent);
                }
            }
        }
        return parents;
    }

    /**
     * As tabelas dadas, pais antes das filhas. Só as tabelas da lista entram na ordem: um pai fora dela não é
     * acrescentado (isso é o "Add related objects"). Se há ciclo, avisa em {@code progress}.
     */
    public static List<String> order(List<String> tables, Map<String, Set<String>> parents,
            ExtractionProgress progress) {
        Set<String> wanted = new LinkedHashSet<>(tables);
        List<String> pending = new ArrayList<>(wanted);
        Set<String> emitted = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        Set<String> cycle = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            String next = null;
            for (String table : pending) {
                if (waiting(table, parents, wanted, emitted) == 0) {
                    next = table;
                    break;
                }
            }
            if (next == null) {
                // Só sobrou ciclo (e o que depende dele): sai primeiro a tabela com menos pais pendentes.
                // Só tabelas do ciclo são candidatas: as que apenas dependem dele esperam o ciclo sair.
                Set<String> stuck = inCycle(pending, parents, wanted);
                cycle.addAll(stuck);
                int fewest = Integer.MAX_VALUE;
                for (String table : pending) {
                    if (!stuck.contains(table)) {
                        continue;
                    }
                    int count = waiting(table, parents, wanted, emitted);
                    if (count < fewest) {
                        fewest = count;
                        next = table;
                    }
                }
            }
            pending.remove(next);
            emitted.add(next);
            ordered.add(next);
        }
        if (!cycle.isEmpty()) {
            progress.warn("FKs circulares entre as tabelas: " + String.join(", ", cycle) + ". Não existe ordem de "
                    + "INSERT que satisfaça todas elas; os dados saem com as tabelas de menos pais pendentes primeiro (uma aproximação, não o mínimo de violações). Recomendação: rode "
                    + "o script de dados com as FKs dessas tabelas desativadas (ou antes de criá-las) e ative-as "
                    + "depois.");
        }
        return ordered;
    }

    /** Quantos pais da tabela, entre as tabelas pedidas, ainda não saíram. */
    private static int waiting(String table, Map<String, Set<String>> parents, Set<String> wanted,
            Set<String> emitted) {
        int count = 0;
        for (String parent : parents.getOrDefault(table, Set.of())) {
            if (wanted.contains(parent) && !emitted.contains(parent)) {
                count++;
            }
        }
        return count;
    }

    /** O que sobra de {@code stuck} tirando, uma vez após outra, as tabelas que nenhuma outra espera: o ciclo. */
    private static Set<String> inCycle(List<String> stuck, Map<String, Set<String>> parents, Set<String> wanted) {
        Set<String> left = new LinkedHashSet<>(stuck);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String table : List.copyOf(left)) {
                boolean hasChild = false;
                for (String other : left) {
                    if (parents.getOrDefault(other, Set.of()).contains(table) && wanted.contains(other)) {
                        hasChild = true;
                        break;
                    }
                }
                if (!hasChild) {
                    left.remove(table);
                    changed = true;
                }
            }
        }
        return left;
    }
}
