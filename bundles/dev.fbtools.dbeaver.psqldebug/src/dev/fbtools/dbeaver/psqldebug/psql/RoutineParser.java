package dev.fbtools.dbeaver.psqldebug.psql;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import dev.fbtools.dbeaver.psqldebug.psql.ast.Expression;
import dev.fbtools.dbeaver.psqldebug.psql.ast.ParameterDeclaration;
import dev.fbtools.dbeaver.psqldebug.psql.ast.RoutineDefinition;
import dev.fbtools.dbeaver.psqldebug.psql.ast.SourcePosition;
import dev.fbtools.dbeaver.psqldebug.psql.parser.PsqlLexer;
import dev.fbtools.dbeaver.psqldebug.psql.parser.PsqlParser;

/** Converte o texto de uma Rotina no AST que o Interpretador executa. */
public final class RoutineParser {

    private RoutineParser() {
    }

    /**
     * @param text {@code CREATE [OR ALTER] PROCEDURE ...}, {@code CREATE [OR ALTER] FUNCTION ...},
     *            {@code EXECUTE BLOCK ...} ou {@code CREATE [OR ALTER] TRIGGER ...} completo. As posições do AST são
     *            deste texto (linha 1 é a primeira dele). Uma trigger sai sem entradas; para executá-la, ver
     *            {@link #parseTrigger}.
     * @throws PsqlSyntaxException no primeiro trecho não reconhecido
     */
    public static RoutineDefinition parse(String text) {
        Parsing parsing = new Parsing(text);
        return parsing.builder.routine(parsing.parser.routineDefinition());
    }

    /**
     * Rotina de package. A Fonte do banco dela é o corpo da package inteiro: as posições do AST são do texto
     * {@code body}. Os valores padrão dos parâmetros de entrada ficam no cabeçalho, e vêm de {@code header}.
     *
     * @param header {@code CREATE [OR ALTER] PACKAGE ...} completo
     * @param body {@code CREATE | RECREATE PACKAGE BODY ...} completo
     * @param kind {@link RoutineDefinition.Kind#PROCEDURE} ou {@link RoutineDefinition.Kind#FUNCTION}
     * @param name nome normalizado da rotina (ver {@link Identifiers#normalize})
     * @throws PsqlSyntaxException no primeiro trecho não reconhecido, ou se o corpo não tiver a rotina
     */
    public static RoutineDefinition parsePackageRoutine(String header, String body, RoutineDefinition.Kind kind,
            String name) {
        Parsing bodyParsing = new Parsing(body);
        PsqlParser.PackageDefinitionContext bodyTree = bodyParsing.parser.packageDefinition();
        RoutineDefinition routine = bodyParsing.builder.packageRoutine(bodyTree, kind, name);
        if (routine == null) {
            throw new PsqlSyntaxException("o corpo da package não tem a rotina " + name,
                    new SourcePosition(1, 1));
        }
        Parsing headerParsing = new Parsing(header);
        List<ParameterDeclaration> declared = headerParsing.builder.packageRoutineInputs(
                headerParsing.parser.packageDefinition(), kind, name);
        if (declared == null || declared.size() != routine.inputs().size()) {
            return routine; // rotina privada: só existe no corpo
        }
        List<ParameterDeclaration> inputs = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            ParameterDeclaration input = routine.inputs().get(i);
            inputs.add(input.defaultValue() != null ? input
                    : new ParameterDeclaration(input.name(), input.type(), input.notNull(),
                            declared.get(i).defaultValue(), input.position()));
        }
        return routine.withInputs(inputs);
    }

    /**
     * Rotina implementada num corpo de package e o trecho dela no texto.
     *
     * @param routine a rotina, sem os valores padrão do cabeçalho (para executá-la, ver {@link #parsePackageRoutine})
     * @param firstLine linha do {@code PROCEDURE} ou {@code FUNCTION} que a começa
     * @param lastLine linha do {@code END} que a termina
     */
    public record PackageRoutine(RoutineDefinition routine, int firstLine, int lastLine) {
    }

    /**
     * Rotinas implementadas no corpo de uma package, na ordem do texto. As posições são do texto {@code body}.
     *
     * @param body {@code CREATE | RECREATE PACKAGE BODY ...} completo
     * @throws PsqlSyntaxException no primeiro trecho não reconhecido
     */
    public static List<PackageRoutine> parsePackageBody(String body) {
        Parsing parsing = new Parsing(body);
        return AstBuilder.packageRoutines(parsing.tokens, parsing.parser.packageDefinition());
    }

    /**
     * Texto de um trecho de um documento maior, como o {@code EXECUTE BLOCK} selecionado no editor SQL, precedido de
     * quebras de linha e espaços até onde ele começa no documento: as posições do AST e do Pré-check passam a ser as
     * do documento.
     *
     * @param line linha do documento em que o trecho começa, a partir de 1
     * @param column coluna dessa linha em que o trecho começa, a partir de 1
     */
    public static String placed(String text, int line, int column) {
        return "\n".repeat(line - 1) + " ".repeat(column - 1) + text;
    }

    /**
     * Coluna da tabela de uma trigger.
     *
     * @param name nome da coluna como está nos metadados
     * @param type tipo da coluna, como texto aceito por {@code CAST(? AS <tipo>)}
     */
    public record Column(String name, String type) {
    }

    /**
     * Trigger isolada: as entradas da Rotina são {@code NEW.<coluna>} e {@code OLD.<coluna>} para cada coluna da
     * tabela, na ordem dada, e {@code INSERTING}, {@code UPDATING} e {@code DELETING}. Sem valor, as de
     * {@code NEW}/{@code OLD} valem {@code NULL} e as de evento, {@code FALSE}. Nenhuma é {@code NOT NULL}: o motor
     * só valida a linha ao gravá-la, depois da trigger.
     *
     * @param text {@code CREATE TRIGGER ...} completo (ver {@link #parse})
     * @param columns colunas da tabela da trigger
     */
    public static RoutineDefinition parseTrigger(String text, List<Column> columns) {
        RoutineDefinition trigger = parse(text);
        if (trigger.kind() != RoutineDefinition.Kind.TRIGGER) {
            throw new PsqlSyntaxException("esperado CREATE TRIGGER", new SourcePosition(1, 1));
        }
        SourcePosition position = trigger.body().position();
        Expression nullValue = new Expression("NULL", List.of(new Expression.Sql("NULL")), position);
        Expression falseValue = new Expression("FALSE", List.of(new Expression.Sql("FALSE")), position);
        List<ParameterDeclaration> inputs = new ArrayList<>();
        for (String record : List.of("NEW", "OLD")) {
            for (Column column : columns) {
                inputs.add(new ParameterDeclaration(record + "." + column.name(), column.type(), false, nullValue,
                        position));
            }
        }
        for (String event : List.of("INSERTING", "UPDATING", "DELETING")) {
            inputs.add(new ParameterDeclaration(event, "BOOLEAN", false, falseValue, position));
        }
        return trigger.withInputs(inputs);
    }

    /**
     * Expressão de um watch, que o Interpretador avalia com as variáveis de um frame. O texto é uma expressão só:
     * com {@code ;}, com {@code ,} fora de parênteses ou com parênteses desbalanceados, ele não é reconhecido, e
     * assim não escapa do {@code SELECT (<expr>) FROM RDB$DATABASE} em que vai ao servidor. O resto da sintaxe é o
     * servidor quem confere. As posições são deste texto.
     *
     * @throws PsqlSyntaxException se o texto não é uma expressão só, ou se usa {@code GEN_ID} (fora
     *             {@code GEN_ID(g, 0)}, que só lê), {@code NEXT VALUE FOR} ou {@code RDB$SET_CONTEXT}, que alteram o
     *             estado do banco mesmo dentro de um {@code SELECT}
     */
    public static Expression parseWatch(String text) {
        Parsing parsing = new Parsing(text);
        return parsing.builder.watch(parsing.parser.watchExpression());
    }

    /** Léxico e parser de um texto, parando no primeiro erro. */
    private static final class Parsing {

        final CommonTokenStream tokens;
        final PsqlParser parser;
        final AstBuilder builder;

        Parsing(String text) {
            PsqlLexer lexer = new PsqlLexer(CharStreams.fromString(text));
            tokens = new CommonTokenStream(lexer);
            parser = new PsqlParser(tokens);
            lexer.removeErrorListeners();
            lexer.addErrorListener(FailFast.INSTANCE);
            parser.removeErrorListeners();
            parser.addErrorListener(FailFast.INSTANCE);
            builder = new AstBuilder(tokens);
        }
    }

    private static final class FailFast extends BaseErrorListener {

        static final FailFast INSTANCE = new FailFast();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                int charPositionInLine, String msg, RecognitionException e) {
            String detail = msg;
            if (offendingSymbol instanceof Token token) {
                detail = token.getType() == Token.EOF ? "fim do texto inesperado"
                        : "trecho não reconhecido: '" + token.getText() + "'";
                Token statement = statementStart(recognizer);
                if (statement != null && statement != token) {
                    detail += " no statement que começa em '" + statement.getText() + "' ("
                            + position(statement) + ")";
                }
            }
            throw new PsqlSyntaxException(detail, new SourcePosition(line, charPositionInLine + 1));
        }

        /** Primeiro token do statement em que o parser estava, que costuma dizer melhor o que não foi reconhecido. */
        private static Token statementStart(Recognizer<?, ?> recognizer) {
            if (!(recognizer instanceof Parser parser)) {
                return null;
            }
            for (ParserRuleContext ctx = parser.getContext(); ctx != null; ctx = ctx.getParent()) {
                if (ctx instanceof PsqlParser.StatementContext) {
                    return ctx.getStart();
                }
            }
            return null;
        }

        private static SourcePosition position(Token token) {
            return new SourcePosition(token.getLine(), token.getCharPositionInLine() + 1);
        }
    }
}
