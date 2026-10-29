package dev.fbtools.dbeaver.psqldebug.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Pilha de savepoints da Rotina, como o motor a mantém (ADR 0001; {@code BlockNode::execute} e
 * {@code ForNode::execute} do Firebird 5):
 * <ul>
 * <li>todo {@code BEGIN ... END} abre um nível; um {@code FOR SELECT} abre outro se o nível atual já tem
 * alterações;</li>
 * <li>quando um nível termina, normalmente ou por {@code LEAVE}/{@code CONTINUE}/{@code EXIT}, o que ele alterou
 * passa para o nível de baixo;</li>
 * <li>um erro deixa os níveis no lugar até chegar a um bloco com {@code WHEN}; se um handler do bloco casar, só os
 * níveis <em>acima</em> do bloco são desfeitos: o que o bloco já fez em statements terminados fica, e o handler
 * roda no nível do bloco;</li>
 * <li>quando um bloco dentro de um handler trata um erro, o motor libera ali mesmo o nível do bloco desse handler
 * (o resto do handler fica sem a proteção dele). Ver {@link #nestedErrorHandled()}.</li>
 * </ul>
 * Os savepoints do servidor são criados só antes da primeira alteração de um nível, e só quando há embaixo um
 * bloco com {@code WHEN} que possa precisar desfazê-lo; sem isso, um erro desfaz a chamada inteira, pelo savepoint
 * da chamada ({@link #beginCall()}).
 * <p>
 * Numa procedure selecionável, cada {@code SUSPEND} esvazia a pilha ({@link #suspended()}).
 */
final class SavepointStack {

    /** Nível da pilha. */
    static final class Level {

        /** Ordem de abertura: como no motor, "acima" de um nível é ter sido aberto depois dele. */
        private final long number;
        /** Bloco com {@code WHEN}: desfaz os níveis acima quando trata um erro. */
        private final boolean catches;
        /** Savepoint do servidor, criado antes da primeira alteração; ou herdado de um nível de cima. */
        private Savepoint savepoint;
        private boolean changes;

        private Level(long number, boolean catches) {
            this.number = number;
            this.catches = catches;
        }
    }

    private final Connection connection;
    /** Níveis abertos; o primeiro é o de cima. */
    private final Deque<Level> levels = new ArrayDeque<>();
    /** Blocos cujo handler está em execução; o primeiro é o mais interno. */
    private final Deque<Level> handlers = new ArrayDeque<>();
    private long opened;
    /**
     * Savepoint da chamada: um erro que sai da Rotina desfaz o que ela fez (desde o último {@code SUSPEND}). Não
     * existe enquanto a Rotina está parada no {@code SUSPEND}.
     */
    private Savepoint call;
    /** Alterações que já passaram para quem chamou a Rotina, no fim do bloco principal ou num {@code SUSPEND}. */
    private boolean changed;

    SavepointStack(Connection connection) {
        this.connection = connection;
    }

    /** Início da chamada da Rotina, como o savepoint que o motor dá a um {@code EXECUTE PROCEDURE}. */
    void beginCall() throws SQLException {
        call = connection.setSavepoint();
    }

    /** Fim normal da chamada: o que ela alterou fica. */
    void endCall() throws SQLException {
        if (call != null) {
            connection.releaseSavepoint(call);
            call = null;
        }
    }

    /**
     * Erro que saiu da Rotina: desfaz o que ela alterou. Falha do rollback fica suprimida em {@code cause}. Parada
     * no {@code SUSPEND} (quem a chamou desistiu das linhas), não há o que desfazer.
     */
    void undoCall(Exception cause) {
        if (call != null) {
            Savepoints.undo(connection, call, cause);
            call = null;
        }
    }

    /**
     * {@code SUSPEND} de procedure selecionável. O motor passa para a busca da linha tudo o que a Rotina alterou até
     * ali, em todos os níveis, e os níveis seguem abertos, mas vazios. Depois disso, um erro, tratado ou não, não
     * desfaz mais o que veio antes do {@code SUSPEND}, e um {@code FOR SELECT} não conta essas alterações.
     * <p>
     * Enquanto a Rotina está parada, nenhum savepoint dela fica aberto no servidor: quem a chamou pode estar
     * percorrendo um {@code FOR SELECT} sobre ela, com savepoints próprios. A Rotina continua com {@link #resumed()}.
     */
    void suspended() throws SQLException {
        for (Level level : levels) { // de cima para baixo: dos savepoints mais novos para os mais velhos
            if (level.savepoint != null) {
                connection.releaseSavepoint(level.savepoint);
                level.savepoint = null;
            }
            changed |= level.changes;
            level.changes = false;
        }
        connection.releaseSavepoint(call);
        call = null;
    }

    /** Busca da linha seguinte: a Rotina continua depois do {@code SUSPEND}. */
    void resumed() throws SQLException {
        call = connection.setSavepoint();
    }

    /**
     * A Rotina passou alterações para quem a chamou desde a última consulta: o nível de quem chama passa a ter
     * alterações.
     */
    boolean takeChanges() {
        boolean result = changed;
        changed = false;
        return result;
    }

    /** @param catches bloco com {@code WHEN} */
    Level push(boolean catches) {
        Level level = new Level(++opened, catches);
        levels.push(level);
        return level;
    }

    /** {@code FOR SELECT}: o motor abre um nível só se o atual já tem alterações. */
    Level pushIfChanged() {
        Level top = levels.peek();
        return top != null && top.changes ? push(false) : null;
    }

    /** Antes de um statement que pode alterar dados: garante o savepoint do nível de cima, se ele puder ser desfeito. */
    void beforeChange() throws SQLException {
        Level top = levels.peek();
        if (top == null || top.savepoint != null) {
            return;
        }
        Iterator<Level> below = levels.iterator();
        below.next();
        while (below.hasNext()) {
            if (below.next().catches) {
                top.savepoint = connection.setSavepoint();
                return;
            }
        }
    }

    /** O último statement alterou dados (o motor não conta DML sem linhas). */
    void changed() {
        Level top = levels.peek();
        if (top != null) {
            top.changes = true;
        }
    }

    /**
     * Fim normal (ou por desvio) do nível: ele e os abertos depois dele passam para o nível de baixo. Um nível que
     * já foi liberado (ver {@link #nestedErrorHandled()}) não muda nada.
     */
    void release(Level level) throws SQLException {
        if (level == null) {
            return;
        }
        while (!levels.isEmpty() && levels.peek().number >= level.number) {
            Level top = levels.pop();
            merge(top, levels.peek());
        }
    }

    /** Erro tratado pelo bloco {@code level}: desfaz os níveis abertos depois dele, que deixam de existir. */
    void rollbackAbove(Level level) throws SQLException {
        Savepoint earliest = null;
        while (!levels.isEmpty() && levels.peek().number > level.number) {
            Level top = levels.pop();
            if (top.savepoint != null) {
                earliest = top.savepoint; // os de baixo foram criados antes
            }
        }
        if (earliest != null) {
            connection.rollback(earliest);
            connection.releaseSavepoint(earliest);
        }
    }

    /** O handler do bloco {@code level} começa. */
    void enterHandler(Level level) {
        handlers.push(level);
    }

    void leaveHandler(Level level) {
        handlers.remove(level);
    }

    /**
     * Um bloco acabou de tratar um erro. Se isso aconteceu dentro do handler de outro bloco, o motor sai ali do laço
     * de execução desse handler, e a saída se propaga pelos laços de todos os handlers em execução até o laço
     * principal da Rotina: cada bloco desses handlers dá o erro por tratado e libera o seu nível (e os de cima). Um
     * erro que depois saia desses handlers não desfaz mais o que eles e os blocos deles fizeram até aqui. O resto
     * dos handlers continua no laço principal, onde isso não se repete; só handlers que comecem depois voltam a
     * contar.
     */
    void nestedErrorHandled() throws SQLException {
        Level outermost = handlers.peekLast();
        if (outermost != null) {
            release(outermost);
            handlers.clear();
        }
    }

    /** Passa o nível fechado para o de baixo, como o {@code RELEASE SAVEPOINT} do motor. */
    private void merge(Level closed, Level below) throws SQLException {
        if (below != null) {
            below.changes |= closed.changes;
        } else {
            changed |= closed.changes;
        }
        if (closed.savepoint == null) {
            return;
        }
        if (below != null && below.savepoint == null) {
            // O nível de baixo não tinha alterado nada antes deste savepoint: ele vale como o savepoint dele.
            below.savepoint = closed.savepoint;
        } else {
            connection.releaseSavepoint(closed.savepoint);
        }
    }
}
