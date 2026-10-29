package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * Procedure como única fonte de um {@code FOR SELECT}: {@code SELECT colunas FROM [pkg.]nome[(argumentos)] [alias]},
 * sem outras cláusulas. Step into elegível, se o nome for de uma procedure (sem argumentos, pode ser também uma
 * tabela, que só o banco distingue).
 *
 * @param packageName nome normalizado da package escrita na chamada, ou {@code null}
 * @param name nome normalizado da procedure
 * @param columns colunas do {@code SELECT}, na ordem; vazio no {@code SELECT *}
 * @param position posição do nome
 */
public record ProcedureSource(String packageName, String name, List<Expression> arguments, List<Column> columns,
        SourcePosition position) {

    public ProcedureSource {
        arguments = List.copyOf(arguments);
        columns = List.copyOf(columns);
    }

    /**
     * Coluna do {@code SELECT}: uma saída da procedure.
     *
     * @param name nome normalizado da saída
     * @param label nome da coluna no resultado (o alias, se houver), que é o campo de um cursor nomeado
     */
    public record Column(String name, String label) {
    }
}
