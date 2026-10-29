package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * {@code BEGIN ... [WHEN ... DO ...] END}.
 *
 * @param handlers cláusulas {@code WHEN} do fim do bloco, na ordem; com alguma, o bloco tem o savepoint implícito
 *            do motor
 */
public record Block(List<Statement> statements, List<Handler> handlers, SourcePosition position)
        implements Statement {

    public Block {
        statements = List.copyOf(statements);
        handlers = List.copyOf(handlers);
    }
}
