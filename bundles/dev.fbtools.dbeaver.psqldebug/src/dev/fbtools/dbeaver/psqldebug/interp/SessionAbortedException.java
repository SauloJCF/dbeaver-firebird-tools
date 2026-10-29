package dev.fbtools.dbeaver.psqldebug.interp;

/**
 * A Sessão de debug foi interrompida a pedido ({@link DebugSession#abort()}), antes de um statement. Não é erro da
 * Rotina: nenhum {@code WHEN} o trata, e a sessão termina com rollback.
 */
public final class SessionAbortedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SessionAbortedException() {
        super("Sessão de debug interrompida");
    }
}
