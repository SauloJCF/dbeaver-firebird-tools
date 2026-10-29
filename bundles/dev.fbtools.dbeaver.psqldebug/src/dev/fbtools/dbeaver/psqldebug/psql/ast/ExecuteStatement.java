package dev.fbtools.dbeaver.psqldebug.psql.ast;

import java.util.List;

/**
 * {@code EXECUTE STATEMENT <sql> [(<parâmetros>)] [WITH …] [INTO …];} local, na mesma conexão e transação.
 *
 * @param sql expressão PSQL que dá o texto do statement dinâmico
 * @param parameters parâmetros, todos posicionais ({@code ?}) ou todos nomeados ({@code nome := valor})
 * @param into nomes normalizados dos alvos do {@code INTO}, na ordem; vazio se não houver
 * @param unsupported opções que o Interpretador não executa (ver {@link UnsupportedConstruct})
 */
public record ExecuteStatement(Expression sql, List<Parameter> parameters, List<String> into,
        List<UnsupportedUse> unsupported, SourcePosition position) implements Statement {

    public ExecuteStatement {
        parameters = List.copyOf(parameters);
        into = List.copyOf(into);
        unsupported = List.copyOf(unsupported);
    }

    /**
     * Parâmetro do statement dinâmico.
     *
     * @param name nome normalizado de um parâmetro nomeado ({@code :nome} no texto dinâmico), ou {@code null}
     *            se posicional
     * @param excess {@code EXCESS}: o parâmetro nomeado pode não aparecer no texto
     */
    public record Parameter(String name, boolean excess, Expression value) {
    }
}
