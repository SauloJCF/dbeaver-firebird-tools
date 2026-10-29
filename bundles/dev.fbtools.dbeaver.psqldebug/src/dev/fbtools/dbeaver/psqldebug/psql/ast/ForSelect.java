package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code [label:] FOR SELECT ... [INTO ...] [AS CURSOR nome] DO statement}: laço sobre um cursor do servidor.
 *
 * @param label nome normalizado do label, ou {@code null}
 * @param select a Fatia DML, com os alvos do {@code INTO} (que pode faltar quando há cursor nomeado)
 * @param cursor nome normalizado do cursor ({@code AS CURSOR}), cujos campos o corpo lê como {@code nome.campo};
 *            ou {@code null}
 * @param procedure a procedure, se ela é a única fonte do {@code SELECT}; senão {@code null}
 * @param position início do statement, que é o label quando houver
 */
public record ForSelect(String label, DmlStatement select, String cursor, ProcedureSource procedure, Statement body,
        SourcePosition position) implements Statement {
}
