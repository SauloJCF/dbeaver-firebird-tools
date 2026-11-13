package dev.fbtools.dbeaver.export.extract;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Opções do Script de dados. {@code dateFormat} e {@code dateTimeFormat} são padrões do {@code DateTimeFormatter}
 * (ex.: {@code yyyy-MM-dd}); {@code null} usa o ISO, e no datetime e no time acrescenta as frações de segundo que o
 * valor tiver (o Firebird guarda até 4 casas); um padrão próprio vale ao pé da letra, e as frações só saem se ele as
 * pedir ({@code SSSS}). {@link #checkFormats} valida os dois. {@code ansiPrefix}: {@code DATE '…'}, {@code TIME '…'} e
 * {@code TIMESTAMP '…'} no lugar do literal sozinho. {@code trimTrailingSpaces}: tira os espaços à direita dos textos
 * (o {@code CHAR} sai com o preenchimento, como no IBExpert). {@code stripControlCharacters}: tira quebras de linha,
 * tabs e demais caracteres de controle dos textos. {@code extractBlobs}: BLOB de texto inline e binário em
 * {@code x'…'}; desligado, as colunas BLOB ficam fora do {@code INSERT} (como no IBExpert) e a extração avisa.
 * {@code commitEvery}: {@code COMMIT WORK} a cada N registros e ao fim de cada tabela. {@code maxFileBytes}: tamanho
 * a partir do qual o script continua num novo arquivo, numerado em sequência; {@code 0} não divide.
 * {@code omitHeader}: sem o {@code SET SQL DIALECT} e o {@code SET NAMES} do início, que o DBeaver recusa.
 */
public record DataOptions(String dateFormat, String dateTimeFormat, boolean ansiPrefix, boolean trimTrailingSpaces,
        boolean stripControlCharacters, boolean extractBlobs, int commitEvery, long maxFileBytes,
        boolean omitHeader) {

    public static final int DEFAULT_COMMIT_EVERY = 500;

    public static final DataOptions DEFAULT =
        new DataOptions(null, null, false, false, false, false, DEFAULT_COMMIT_EVERY, 0, false);

    public DataOptions {
        if (commitEvery < 1) {
            throw new IllegalArgumentException("commitEvery precisa ser pelo menos 1: " + commitEvery);
        }
        if (maxFileBytes < 0) {
            throw new IllegalArgumentException("maxFileBytes não pode ser negativo: " + maxFileBytes);
        }
    }

    /**
     * Confere os padrões de data e de data e hora formatando um valor de exemplo do tipo certo: um padrão que compila
     * mas pede campos que o tipo não tem (ex.: {@code HH} numa data) só falharia no meio da extração.
     *
     * @return a mensagem do problema, ou {@code null} se os dois são válidos (ou vazios)
     */
    public static String checkFormats(String dateFormat, String dateTimeFormat) {
        try {
            if (dateFormat != null && !dateFormat.isBlank()) {
                DateTimeFormatter.ofPattern(dateFormat.strip()).format(LocalDate.of(2024, 3, 10));
            }
        } catch (RuntimeException e) {
            return "Formato de data inválido para uma data: " + e.getMessage();
        }
        try {
            if (dateTimeFormat != null && !dateTimeFormat.isBlank()) {
                DateTimeFormatter.ofPattern(dateTimeFormat.strip()).format(LocalDateTime.of(2024, 3, 10, 8, 5, 30));
            }
        } catch (RuntimeException e) {
            return "Formato de data e hora inválido: " + e.getMessage();
        }
        return null;
    }

    public DataOptions withFormats(String date, String dateTime) {
        return new DataOptions(date, dateTime, ansiPrefix, trimTrailingSpaces, stripControlCharacters, extractBlobs,
                commitEvery, maxFileBytes, omitHeader);
    }

    public DataOptions withAnsiPrefix(boolean value) {
        return new DataOptions(dateFormat, dateTimeFormat, value, trimTrailingSpaces, stripControlCharacters,
                extractBlobs, commitEvery, maxFileBytes, omitHeader);
    }

    public DataOptions withTexts(boolean trim, boolean strip) {
        return new DataOptions(dateFormat, dateTimeFormat, ansiPrefix, trim, strip, extractBlobs, commitEvery,
                maxFileBytes, omitHeader);
    }

    public DataOptions withBlobs(boolean value) {
        return new DataOptions(dateFormat, dateTimeFormat, ansiPrefix, trimTrailingSpaces, stripControlCharacters,
                value, commitEvery, maxFileBytes, omitHeader);
    }

    public DataOptions withCommitEvery(int value) {
        return new DataOptions(dateFormat, dateTimeFormat, ansiPrefix, trimTrailingSpaces, stripControlCharacters,
                extractBlobs, value, maxFileBytes, omitHeader);
    }

    public DataOptions withMaxFileBytes(long value) {
        return new DataOptions(dateFormat, dateTimeFormat, ansiPrefix, trimTrailingSpaces, stripControlCharacters,
                extractBlobs, commitEvery, value, omitHeader);
    }

    public DataOptions withOmitHeader(boolean value) {
        return new DataOptions(dateFormat, dateTimeFormat, ansiPrefix, trimTrailingSpaces, stripControlCharacters,
                extractBlobs, commitEvery, maxFileBytes, value);
    }
}
