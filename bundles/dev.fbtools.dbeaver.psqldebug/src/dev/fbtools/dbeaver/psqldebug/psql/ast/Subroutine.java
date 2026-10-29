package dev.fbtools.dbeaver.psqldebug.psql.ast;

/**
 * Sub-rotina interna ({@code DECLARE PROCEDURE}/{@code DECLARE FUNCTION}) e o texto da cópia dela no Package
 * temporário de debug (ADR 0003). As posições do AST dela são do texto da Rotina que a declara.
 * <p>
 * No Firebird 5, a sub-rotina enxerga os parâmetros da Rotina que a declara e as variáveis locais declaradas antes
 * dela (não os cursores); ver {@code SubroutineScopes}. Dentro dela, uma rotina de package só é alcançada com o nome
 * da package.
 *
 * @param routine a sub-rotina, sem package; numa declaração antecipada, os valores padrão das entradas vêm dela
 * @param header {@code PROCEDURE|FUNCTION nome <assinatura>}, com os valores padrão, para o cabeçalho do package
 * @param implementation {@code PROCEDURE|FUNCTION nome <assinatura sem valores padrão> AS <corpo>}, para o corpo do
 *            package, que não aceita valor padrão de rotina já declarada no cabeçalho
 * @param position posição do {@code DECLARE} da implementação
 */
public record Subroutine(RoutineDefinition routine, String header, String implementation, SourcePosition position) {
}
