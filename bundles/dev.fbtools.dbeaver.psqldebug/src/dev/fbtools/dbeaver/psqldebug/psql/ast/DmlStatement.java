package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * Fatia DML: {@code SELECT … INTO}, {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code MERGE} ou
 * {@code UPDATE OR INSERT}, tratada como texto opaco que o servidor executa.
 *
 * @param sql texto enviado ao servidor, já sem a cláusula {@code INTO} das variáveis; os candidatos a variável
 *            são só as referências {@code :var}
 * @param into nomes normalizados dos alvos do {@code INTO} (de um {@code SELECT} ou de um
 *            {@code RETURNING … INTO}), na ordem; vazio se não houver
 * @param unsupported cláusulas que o Interpretador não executa (ver {@link UnsupportedConstruct})
 */
public record DmlStatement(Kind kind, Expression sql, List<String> into, List<UnsupportedUse> unsupported,
        SourcePosition position) implements Statement {

    /** Tipo de DML, pelo início do statement. */
    public enum Kind {
        SELECT, INSERT, UPDATE, DELETE, MERGE, UPDATE_OR_INSERT
    }

    public DmlStatement {
        into = List.copyOf(into);
        unsupported = List.copyOf(unsupported);
    }
}
