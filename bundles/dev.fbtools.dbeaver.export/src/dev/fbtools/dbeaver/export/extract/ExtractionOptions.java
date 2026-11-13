package dev.fbtools.dbeaver.export.extract;

/**
 * Opções do Script de metadata. {@code createDatabase}: escreve o {@code CREATE DATABASE} (sem usuário nem senha, ADR
 * 0009). {@code alwaysCharacterSet}: {@code CHARACTER SET}/{@code COLLATE} em todas as colunas de texto, e não só
 * quando diferem do padrão do banco. {@code computedBySeparate}: colunas {@code COMPUTED BY} fora do
 * {@code CREATE TABLE}, em {@code ALTER TABLE ... ADD}. {@code alwaysQuote}: todos os identificadores entre aspas.
 * {@code createOrAlter}: {@code CREATE OR ALTER} nas views, triggers e nos corpos de procedures e functions.
 * {@code noSetTerm}: sem {@code SET TERM}; cada rotina termina em {@code END;}. Nem o {@code isql} nem o DBeaver
 * executam esse formato (cortam a rotina no primeiro {@code ;} interno); serve só a quem
 * lê o script ou a uma ferramenta que reconheça o bloco {@code BEGIN ... END}. {@code omitHeader}: sem o
 * {@code SET SQL DIALECT} e o {@code SET NAMES} do início, que o DBeaver recusa (são comandos do {@code isql}).
 * {@code useSequence}: {@code CREATE SEQUENCE} no lugar de {@code CREATE GENERATOR}; um generator com {@code START WITH}
 * ou {@code INCREMENT} fora do padrão sai como {@code SEQUENCE} de qualquer jeito, que o {@code GENERATOR} não os aceita.
 * {@code setGenerators}: {@code SET GENERATOR ... TO} com o valor atual de cada generator e o {@code RESTART WITH} das
 * identities. {@code descriptions}: as descrições, sempre como {@code COMMENT ON}. {@code decodeDomains}: colunas e
 * parâmetros saem com o tipo do domínio, e não com o nome dele; o {@code CHECK} do domínio não vai junto (o
 * {@code VALUE} dele só existe no domínio), e a extração avisa. {@code grants}: os privilégios.
 * {@code grantsOnlySelected}: só os dos objetos da seleção.
 */
public record ExtractionOptions(boolean createDatabase, boolean alwaysCharacterSet, boolean computedBySeparate,
        boolean alwaysQuote, boolean createOrAlter, boolean noSetTerm, boolean omitHeader, boolean useSequence,
        boolean setGenerators, boolean descriptions, boolean decodeDomains, boolean grants,
        boolean grantsOnlySelected) {

    public static final ExtractionOptions DEFAULT =
        new ExtractionOptions(false, false, false, false, false, false, false, false);

    /**
     * Os oito primeiros e, dos demais, os padrões: Set generators, descrições e privilégios (só dos selecionados)
     * ligados, Decode domains desligado. A aba Options parte destes mesmos padrões.
     */
    public ExtractionOptions(boolean createDatabase, boolean alwaysCharacterSet, boolean computedBySeparate,
            boolean alwaysQuote, boolean createOrAlter, boolean noSetTerm, boolean omitHeader, boolean useSequence) {
        this(createDatabase, alwaysCharacterSet, computedBySeparate, alwaysQuote, createOrAlter, noSetTerm,
                omitHeader, useSequence, true, true, false, true, true);
    }

    public ExtractionOptions withSetGenerators(boolean value) {
        return withLater(value, descriptions, decodeDomains, grants, grantsOnlySelected);
    }

    public ExtractionOptions withDescriptions(boolean value) {
        return withLater(setGenerators, value, decodeDomains, grants, grantsOnlySelected);
    }

    public ExtractionOptions withDecodeDomains(boolean value) {
        return withLater(setGenerators, descriptions, value, grants, grantsOnlySelected);
    }

    public ExtractionOptions withGrants(boolean value, boolean onlySelected) {
        return withLater(setGenerators, descriptions, decodeDomains, value, onlySelected);
    }

    /** Uma cópia com as cinco últimas opções trocadas. */
    private ExtractionOptions withLater(boolean setGenerators, boolean descriptions, boolean decodeDomains,
            boolean grants, boolean grantsOnlySelected) {
        return new ExtractionOptions(createDatabase, alwaysCharacterSet, computedBySeparate, alwaysQuote,
                createOrAlter, noSetTerm, omitHeader, useSequence, setGenerators, descriptions, decodeDomains, grants,
                grantsOnlySelected);
    }

    /** Com {@code GENERATOR}. */
    public ExtractionOptions(boolean createDatabase, boolean alwaysCharacterSet, boolean computedBySeparate,
            boolean alwaysQuote, boolean createOrAlter, boolean noSetTerm, boolean omitHeader) {
        this(createDatabase, alwaysCharacterSet, computedBySeparate, alwaysQuote, createOrAlter, noSetTerm,
                omitHeader, false);
    }

    /** Com o cabeçalho. */
    public ExtractionOptions(boolean createDatabase, boolean alwaysCharacterSet, boolean computedBySeparate,
            boolean alwaysQuote, boolean createOrAlter, boolean noSetTerm) {
        this(createDatabase, alwaysCharacterSet, computedBySeparate, alwaysQuote, createOrAlter, noSetTerm, false);
    }

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
