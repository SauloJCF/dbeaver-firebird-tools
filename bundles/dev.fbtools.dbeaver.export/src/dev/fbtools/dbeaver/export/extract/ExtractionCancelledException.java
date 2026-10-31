package dev.fbtools.dbeaver.export.extract;

/** A extração foi cancelada pelo usuário. */
public final class ExtractionCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExtractionCancelledException() {
        super("Extração cancelada", null, false, false);
    }
}
