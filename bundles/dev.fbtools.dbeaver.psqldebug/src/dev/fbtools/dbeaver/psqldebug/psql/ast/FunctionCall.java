package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * {@code [pkg.]nome(argumentos)} que é a expressão inteira de uma atribuição ({@code x = F(...)}): Step into
 * elegível, se o nome for de uma function PSQL (pode ser também uma função interna, como {@code UPPER}, que só o
 * banco distingue).
 *
 * @param packageName nome normalizado da package escrita na chamada, ou {@code null}
 * @param name nome normalizado da function
 * @param position posição do nome
 */
public record FunctionCall(String packageName, String name, List<Expression> arguments, SourcePosition position) {

    public FunctionCall {
        arguments = List.copyOf(arguments);
    }
}
