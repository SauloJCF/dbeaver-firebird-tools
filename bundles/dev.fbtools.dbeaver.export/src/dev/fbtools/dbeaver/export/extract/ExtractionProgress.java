package dev.fbtools.dbeaver.export.extract;

/** Progresso e cancelamento da extração; o job de background traduz para o monitor do Eclipse. */
public interface ExtractionProgress {

    /** Nunca cancela e ignora o progresso. */
    ExtractionProgress NONE = new ExtractionProgress() {
    };

    /** Chamado uma vez, com o total de objetos a extrair. */
    default void begin(int totalObjects) {
    }

    /** Chamado a cada objeto extraído. */
    default void extracted(String description) {
    }

    /** Algo que o usuário deve saber ao fim da extração (ex.: conexão com charset diferente do banco). */
    default void warn(String message) {
    }

    default boolean isCancelled() {
        return false;
    }

    /** @throws ExtractionCancelledException se o usuário cancelou */
    default void checkCancelled() {
        if (isCancelled()) {
            throw new ExtractionCancelledException();
        }
    }
}
