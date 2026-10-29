package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * {@code IN AUTONOMOUS TRANSACTION DO statement}. Reconhecido só para o Pré-check recusar
 * ({@link UnsupportedConstruct#IN_AUTONOMOUS_TRANSACTION}); o Interpretador não executa.
 */
public record InAutonomousTransaction(Statement body, SourcePosition position) implements Statement {
}
