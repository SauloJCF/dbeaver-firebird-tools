package dev.fbtools.dbeaver.export.extract;

/**
 * Opções do Script de metadata. {@code createDatabase}: escreve o {@code CREATE DATABASE} (sem usuário nem senha, ADR
 * 0009). {@code alwaysCharacterSet}: {@code CHARACTER SET}/{@code COLLATE} em todas as colunas de texto, e não só
 * quando diferem do padrão do banco. {@code computedBySeparate}: colunas {@code COMPUTED BY} fora do
 * {@code CREATE TABLE}, em {@code ALTER TABLE ... ADD}. {@code alwaysQuote}: todos os identificadores entre aspas.
 * {@code createOrAlter}: {@code CREATE OR ALTER} nas views e nos corpos de procedures e functions (e, nos próximos
 * issues, de triggers). {@code noSetTerm}: sem {@code SET TERM}; cada rotina termina em {@code END;} e quem executa o
 * script precisa reconhecer o bloco {@code BEGIN ... END} (como o DBeaver), porque o {@code isql} não reconhece.
 */
public record ExtractionOptions(boolean createDatabase, boolean alwaysCharacterSet, boolean computedBySeparate,
        boolean alwaysQuote, boolean createOrAlter, boolean noSetTerm) {

    public static final ExtractionOptions DEFAULT = new ExtractionOptions(false, false, false, false, false, false);

    /** Com {@code SET TERM}. */
    public ExtractionOptions(boolean createDatabase, boolean alwaysCharacterSet, boolean computedBySeparate,
            boolean alwaysQuote, boolean createOrAlter) {
        this(createDatabase, alwaysCharacterSet, computedBySeparate, alwaysQuote, createOrAlter, false);
    }

    /** Sem {@code CREATE OR ALTER}. */
    public ExtractionOptions(boolean createDatabase, boolean alwaysCharacterSet, boolean computedBySeparate,
            boolean alwaysQuote) {
        this(createDatabase, alwaysCharacterSet, computedBySeparate, alwaysQuote, false);
    }

    /** O nome como vai no script, conforme {@code alwaysQuote}. */
    String quote(String name) {
        return Identifiers.quote(name, alwaysQuote);
    }
}
