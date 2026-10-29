package dev.fbtools.dbeaver.psqldebug.debug;

import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;

/**
 * Texto de Rotina numa conexão do DBeaver, de onde são as linhas dos breakpoints e dos stack frames: o que o editor do
 * objeto mostra, que é a Fonte do banco. As sub-rotinas ficam no texto da Rotina que as declara; uma rotina de
 * package, no corpo da package, que o console aberto por "Abrir Package" mostra. Um {@code EXECUTE BLOCK} não existe
 * no banco: o texto é o do editor SQL de onde ele sai.
 *
 * @param dataSourceId id da conexão do DBeaver
 * @param name nome da Rotina (ou da package) como está nos metadados; num {@code EXECUTE BLOCK}, o nome do editor SQL
 *            (ou {@code null}, se não se sabe de que editor ele é)
 */
public record SourceId(String dataSourceId, Kind kind, String name) {

    /** De que objeto é o texto. */
    public enum Kind {
        PROCEDURE, FUNCTION, PACKAGE, TRIGGER, EXECUTE_BLOCK
    }

    /** @param routine Rotina de cujo texto são as posições (ver {@code Frame.sourceRoutine()}) */
    public static SourceId of(String dataSourceId, RoutineDefinition routine) {
        if (routine.packageName() != null) {
            return new SourceId(dataSourceId, Kind.PACKAGE, routine.packageName());
        }
        Kind kind = switch (routine.kind()) {
            case PROCEDURE -> Kind.PROCEDURE;
            case FUNCTION -> Kind.FUNCTION;
            case TRIGGER -> Kind.TRIGGER;
            case EXECUTE_BLOCK -> Kind.EXECUTE_BLOCK;
        };
        return new SourceId(dataSourceId, kind, routine.name());
    }
}
