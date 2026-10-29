package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * Rotina: cabeçalho (parâmetros de entrada, saídas ou tipo de retorno), declarações e o bloco principal.
 *
 * @param packageName package da rotina, ou {@code null} fora de package
 * @param name nome da rotina; {@code null} num {@code EXECUTE BLOCK}
 * @param inputs parâmetros de entrada; numa trigger isolada, {@code NEW.*}, {@code OLD.*}, {@code INSERTING},
 *            {@code UPDATING} e {@code DELETING} (ver {@link Trigger})
 * @param outputs parâmetros de saída ({@code RETURNS (...)}); vazio numa function e numa trigger
 * @param returnValue tipo de retorno de uma function, com o nome dela; {@code null} nos outros tipos
 * @param selectable tem {@code SUSPEND} (o motor a cadastra como procedure selecionável)
 * @param unsupported construções recusadas que aparecem dentro de expressões, fora de qualquer statement em
 *            particular, ou no cabeçalho (trigger de banco ou de DDL); ver {@link UnsupportedConstruct}
 * @param trigger cabeçalho de uma trigger; {@code null} nos outros tipos
 * @param subroutines sub-rotinas internas implementadas na Rotina, na ordem da Fonte; vazio numa sub-rotina, que não
 *            pode ter outras
 */
public record RoutineDefinition(
        Kind kind,
        String packageName,
        String name,
        List<ParameterDeclaration> inputs,
        List<ParameterDeclaration> outputs,
        ParameterDeclaration returnValue,
        List<VariableDeclaration> declarations,
        List<CursorDeclaration> cursors,
        Block body,
        boolean selectable,
        List<UnsupportedUse> unsupported,
        Trigger trigger,
        List<Subroutine> subroutines) {

    /** Tipo de Rotina raiz. */
    public enum Kind {
        PROCEDURE, FUNCTION, EXECUTE_BLOCK, TRIGGER
    }

    public RoutineDefinition {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        declarations = List.copyOf(declarations);
        cursors = List.copyOf(cursors);
        unsupported = List.copyOf(unsupported);
        subroutines = List.copyOf(subroutines);
    }

    /**
     * Sub-rotina com o tipo e o nome dados.
     *
     * @param kind {@link Kind#PROCEDURE} ou {@link Kind#FUNCTION}
     * @param name nome normalizado
     * @return {@code null} se a Rotina não implementa essa sub-rotina
     */
    public Subroutine subroutine(Kind kind, String name) {
        for (Subroutine subroutine : subroutines) {
            if (subroutine.routine().kind() == kind && subroutine.routine().name().equals(name)) {
                return subroutine;
            }
        }
        return null;
    }

    /** Nome para mensagens: {@code P}, {@code PKG.P} ou {@code EXECUTE BLOCK}. */
    public String displayName() {
        if (kind == Kind.EXECUTE_BLOCK) {
            return "EXECUTE BLOCK";
        }
        return packageName == null ? name : packageName + "." + name;
    }

    /** A mesma Rotina com outras entradas. */
    public RoutineDefinition withInputs(List<ParameterDeclaration> newInputs) {
        return new RoutineDefinition(kind, packageName, name, newInputs, outputs, returnValue, declarations, cursors,
                body, selectable, unsupported, trigger, subroutines);
    }

    /**
     * Cabeçalho de uma trigger de tabela: {@code FOR tabela {BEFORE | AFTER} evento [OR evento ...]}. Numa trigger
     * de banco ou de DDL, que o Pré-check recusa, a tabela é {@code null} e não há eventos.
     *
     * @param table nome normalizado da tabela (ou view)
     * @param before {@code BEFORE}; senão {@code AFTER}
     */
    public record Trigger(String table, boolean before, List<Event> events) {

        /** Evento de DML que dispara a trigger. */
        public enum Event {
            INSERT, UPDATE, DELETE
        }

        public Trigger {
            events = List.copyOf(events);
        }
    }
}
