package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code SUSPEND;}: numa procedure selecionável, emite uma linha com os valores atuais dos parâmetros de saída e
 * continua; num {@code EXECUTE PROCEDURE}, devolve esses valores e termina a Rotina.
 */
public record Suspend(SourcePosition position) implements Statement {
}
