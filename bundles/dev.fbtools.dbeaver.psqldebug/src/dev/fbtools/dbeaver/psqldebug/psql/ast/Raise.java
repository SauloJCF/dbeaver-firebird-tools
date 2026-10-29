package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * {@code EXCEPTION [nome [mensagem | USING (argumentos)]];}. Sem nome, relança o erro tratado pelo handler.
 *
 * @param exception nome normalizado da exceção, ou {@code null} no relançamento
 * @param message expressão da mensagem que substitui a da exceção, ou {@code null}
 * @param arguments valores dos {@code @1}, {@code @2}... da mensagem ({@code USING}); vazio se não houver
 */
public record Raise(String exception, Expression message, List<Expression> arguments, SourcePosition position)
        implements Statement {

    public Raise {
        arguments = List.copyOf(arguments);
    }
}
