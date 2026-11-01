package dev.fbtools.dbeaver.export.extract;

/**
 * Opções do Script de metadata. {@code createDatabase}: escreve o {@code CREATE DATABASE} (sem usuário nem senha, ADR
 * 0009). {@code alwaysCharacterSet}: {@code CHARACTER SET}/{@code COLLATE} em todas as colunas de texto, e não só
 * quando diferem do padrão do banco. {@code computedBySeparate}: colunas {@code COMPUTED BY} fora do
 * {@code CREATE TABLE}, em {@code ALTER TABLE ... ADD}. {@code alwaysQuote}: todos os identificadores entre aspas.
 */
public record ExtractionOptions(boolean createDatabase, boolean alwaysCharacterSet, boolean computedBySeparate,
        boolean alwaysQuote) {

    public static final ExtractionOptions DEFAULT = new ExtractionOptions(false, false, false, false);

    /** O nome como vai no script, conforme {@code alwaysQuote}. */
    String quote(String name) {
        return Identifiers.quote(name, alwaysQuote);
    }
}
