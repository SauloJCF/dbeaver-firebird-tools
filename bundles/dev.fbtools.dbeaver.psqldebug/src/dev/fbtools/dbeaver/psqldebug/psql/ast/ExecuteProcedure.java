package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * {@code EXECUTE PROCEDURE [pkg.]nome [(argumentos)] [RETURNING_VALUES alvos];}: Step into elegível.
 *
 * @param packageName nome normalizado da package escrita na chamada, ou {@code null}
 * @param name nome normalizado da procedure
 * @param arguments valores das entradas, na ordem; as que faltarem no fim usam o valor padrão do parâmetro
 * @param returningValues nomes normalizados dos alvos das saídas, na ordem; vazio se não houver
 */
public record ExecuteProcedure(String packageName, String name, List<Expression> arguments,
        List<String> returningValues, SourcePosition position) implements Statement {

    public ExecuteProcedure {
        arguments = List.copyOf(arguments);
        returningValues = List.copyOf(returningValues);
    }
}
