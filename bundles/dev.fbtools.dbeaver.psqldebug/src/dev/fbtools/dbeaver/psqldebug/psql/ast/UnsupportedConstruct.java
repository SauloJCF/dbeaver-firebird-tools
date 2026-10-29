package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * Construções que a gramática reconhece mas o Interpretador não executa: o Pré-check recusa o launch quando a
 * Rotina usa alguma (ADR 0001: nada é executado "mais ou menos"). Esta é a lista única das construções recusadas,
 * fonte da seção "Fora do MVP" da spec.
 *
 * <p>
 * O que a gramática nem reconhece também é recusado pelo Pré-check, como trecho não reconhecido, com a posição
 * do erro de sintaxe.
 */
public enum UnsupportedConstruct {

    /** {@code UPDATE}/{@code DELETE} posicionado: o cursor é do servidor e o Interpretador não o expõe. */
    WHERE_CURRENT_OF("WHERE CURRENT OF"),

    /** Conexão a outro banco, fora da Sessão de debug. */
    EXECUTE_STATEMENT_ON_EXTERNAL("EXECUTE STATEMENT ... ON EXTERNAL"),

    /** Transação própria, fora da transação única da Sessão de debug. */
    EXECUTE_STATEMENT_AUTONOMOUS("EXECUTE STATEMENT ... WITH AUTONOMOUS TRANSACTION"),

    /** Outra conexão com outro usuário/role, fora da Sessão de debug. */
    EXECUTE_STATEMENT_AS_USER("EXECUTE STATEMENT ... AS USER / PASSWORD / ROLE"),

    /**
     * {@code ROW_COUNT} dentro de uma Fatia DML: o motor dá o número de linhas que o próprio statement já processou
     * (0, 1, 2… a cada linha), o que uma Fatia opaca não reproduz. Fora de DML ele vale o da DML anterior.
     */
    ROW_COUNT_IN_DML("ROW_COUNT dentro de SELECT/INSERT/UPDATE/DELETE/MERGE"),

    /** Transação própria, fora da transação única da Sessão de debug. */
    IN_AUTONOMOUS_TRANSACTION("IN AUTONOMOUS TRANSACTION"),

    /**
     * Variável usada no {@code SELECT} de um cursor alterada com ele aberto (no corpo do {@code FOR SELECT}, no
     * próprio {@code INTO} dele, ou entre o {@code OPEN} e o {@code CLOSE}). O motor relê a variável quando busca
     * cada linha, então a mudança altera as linhas seguintes; o cursor do Interpretador recebe os valores uma vez,
     * na abertura. O Pré-check acha os casos do {@code FOR SELECT}; os de cursor declarado são recusados durante a
     * execução, quando acontecem.
     */
    CURSOR_VARIABLE_CHANGED("alteração de variável usada pelo SELECT de um cursor aberto"),

    /**
     * {@code RDB$ERROR(...)}: o servidor só conhece o erro de um handler que ele mesmo executa. Os handlers são do
     * Interpretador, então no servidor a função daria sempre {@code NULL}. {@code GDSCODE}, {@code SQLCODE} e
     * {@code SQLSTATE} são suportados.
     */
    RDB_ERROR("RDB$ERROR"),

    /**
     * Trigger de banco ({@code ON CONNECT}, {@code ON DISCONNECT}, {@code ON TRANSACTION ...}): só a trigger de
     * tabela é depurável, como Trigger isolada.
     */
    DATABASE_TRIGGER("trigger de banco (ON CONNECT, ON DISCONNECT, ON TRANSACTION)"),

    /** Trigger de DDL ({@code BEFORE | AFTER ANY DDL STATEMENT}, {@code ... CREATE TABLE} etc.). */
    DDL_TRIGGER("trigger de DDL"),

    /**
     * Chamada dentro de expressão ou de Fatia DML a uma sub-rotina que usa variáveis da Rotina que a declara (o
     * Firebird 5 permite). A chamada roda no servidor, na cópia do Package temporário de debug, onde essas variáveis
     * não existem. Nas chamadas Step into elegíveis, a sub-rotina é interpretada e funciona.
     */
    SUBROUTINE_USING_PARENT_VARIABLES("chamada, dentro de expressão ou DML, de sub-rotina que usa variáveis da "
            + "Rotina que a declara");

    private final String syntax;

    UnsupportedConstruct(String syntax) {
        this.syntax = syntax;
    }

    /** Como a construção aparece na Fonte, para a mensagem do Pré-check. */
    public String syntax() {
        return syntax;
    }
}
