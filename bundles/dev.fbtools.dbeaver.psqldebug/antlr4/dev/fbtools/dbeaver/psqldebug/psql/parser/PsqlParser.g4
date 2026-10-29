/*
 * Gramática da camada procedural do PSQL do Firebird 5 (ADR 0001).
 *
 * Tipos e expressões são sequências opacas de tokens: o servidor é quem os entende (ADR 0002). A gramática
 * pode ser tolerante, porque a Fonte do banco já foi compilada pelo servidor; o que ela não reconhece é
 * recusado, nunca executado "mais ou menos".
 */
parser grammar PsqlParser;

options { tokenVocab = PsqlLexer; }

// Rotina avulsa: procedure, function, EXECUTE BLOCK ou trigger.
routineDefinition
    : ( procedureDefinition | functionDefinition | executeBlock | triggerDefinition ) SEMI? EOF
    ;

// CREATE TRIGGER nome <cabeçalho> AS ...: o cabeçalho (FOR tabela, fase, eventos, POSITION, ou ON CONNECT etc. numa
// trigger de banco) é separado pelo AstBuilder.
triggerDefinition
    : createOrAlter TRIGGER identifier triggerHeaderPart* AS routineBody
    ;

triggerHeaderPart
    : ~( AS | SEMI )
    ;

procedureDefinition
    : createOrAlter PROCEDURE identifier procedureSignature AS routineBody
    ;

functionDefinition
    : createOrAlter FUNCTION identifier functionSignature AS routineBody
    ;

// EXECUTE BLOCK: os parâmetros de entrada são "nome tipo = ?", ligados pelo cliente.
executeBlock
    : EXECUTE BLOCK inputParameters? outputParameters? AS routineBody
    ;

// CREATE PACKAGE nome AS BEGIN <declarações> END (cabeçalho) ou CREATE PACKAGE BODY nome AS BEGIN <rotinas> END.
packageDefinition
    : createOrAlter PACKAGE BODY? identifier AS BEGIN packageRoutine* END SEMI? EOF
    ;

// No cabeçalho, só a assinatura; no corpo, a rotina inteira.
packageRoutine
    : PROCEDURE identifier procedureSignature ( AS routineBody | SEMI )
    | FUNCTION identifier functionSignature ( AS routineBody | SEMI )
    ;

createOrAlter
    : CREATE ( OR ALTER )?
    | ALTER
    | RECREATE
    ;

procedureSignature
    : inputParameters? outputParameters?
    ;

// SQL SECURITY DEFINER | INVOKER no fim (as três palavras saem como IDENTIFIER). Sem NOT NULL antes, o returnType
// engole o SQL SECURITY, e o AstBuilder o separa do tipo.
functionSignature
    : inputParameters? RETURNS returnType notNull? collation? DETERMINISTIC? sqlSecurity?
    ;

sqlSecurity
    : IDENTIFIER IDENTIFIER IDENTIFIER
    ;

returnType
    : returnTypePart+
    ;

returnTypePart
    : ~( AS | NOT | COLLATE | DETERMINISTIC | SEMI | LPAREN | RPAREN )
    | LPAREN ~RPAREN* RPAREN
    ;

inputParameters
    : LPAREN parameterDeclaration ( COMMA parameterDeclaration )* RPAREN
    ;

outputParameters
    : RETURNS LPAREN parameterDeclaration ( COMMA parameterDeclaration )* RPAREN
    ;

parameterDeclaration
    : identifier typeSpec notNull? collation? defaultValue?
    ;

routineBody
    : declaration* block
    ;

declaration
    : variableDeclaration
    | cursorDeclaration
    | subroutineDeclaration
    ;

// DECLARE PROCEDURE | FUNCTION nome <assinatura> AS <corpo>, ou só a assinatura com ';' (declaração antecipada). O
// Firebird não aceita sub-rotina dentro de sub-rotina; o AstBuilder recusa.
subroutineDeclaration
    : DECLARE PROCEDURE identifier procedureSignature ( AS routineBody | SEMI )
    | DECLARE FUNCTION identifier functionSignature ( AS routineBody | SEMI )
    ;

variableDeclaration
    : DECLARE VARIABLE? identifier typeSpec notNull? collation? defaultValue? SEMI
    ;

// DECLARE c [SCROLL | NO SCROLL] CURSOR FOR (SELECT ...); SCROLL e NO são conferidos pelo AstBuilder.
cursorDeclaration
    : DECLARE identifier ( IDENTIFIER IDENTIFIER? )? CURSOR FOR LPAREN expressionContent* RPAREN SEMI
    ;

typeSpec
    : typePart+
    ;

// CURSOR nunca faz parte de um tipo: é o que separa a declaração de cursor da de variável.
typePart
    : ~( EQ | DEFAULT | NOT | COLLATE | COMMA | SEMI | LPAREN | RPAREN | CURSOR )
    | LPAREN ~RPAREN* RPAREN
    ;

notNull
    : NOT NULL
    ;

collation
    : COLLATE identifier
    ;

defaultValue
    : ( EQ | DEFAULT ) expression
    ;

block
    : BEGIN statement* handler* END SEMI?
    ;

// WHEN <condição>, ... DO statement, no fim do bloco.
handler
    : WHEN errorCondition ( COMMA errorCondition )* DO statement
    ;

// EXCEPTION nome, SQLCODE [-]n, GDSCODE nome, SQLSTATE 'xxxxx' ou ANY. SQLCODE, GDSCODE, SQLSTATE e ANY são
// reservadas (saem como IDENTIFIER) e o AstBuilder confere qual veio.
errorCondition
    : EXCEPTION identifier
    | IDENTIFIER ( OPERATOR? NUMBER | IDENTIFIER | STRING )?
    ;

statement
    : block
    | assignment
    | ifStatement
    | whileStatement
    | forStatement
    | leaveStatement
    | continueStatement
    | exitStatement
    | dmlStatement
    | executeStatement
    | executeProcedure
    | openStatement
    | fetchStatement
    | closeStatement
    | raiseStatement
    | suspendStatement
    | returnStatement
    | autonomousTransaction
    ;

// FOR SELECT ... [INTO ...] [AS CURSOR c] DO e FOR EXECUTE STATEMENT ... INTO ... DO. Texto opaco até o DO; o
// AstBuilder separa o SELECT (ou o EXECUTE STATEMENT), os alvos do INTO e o nome do cursor.
forStatement
    : label? FOR ( SELECT | WITH | EXECUTE ) forContent* DO statement
    ;

forContent
    : LPAREN expressionContent* RPAREN
    | ~( DO | LPAREN | RPAREN | SEMI )
    ;

openStatement
    : OPEN identifier SEMI
    ;

// FETCH [NEXT | PRIOR | FIRST | LAST | ABSOLUTE n | RELATIVE n FROM] c [INTO ...]; separado pelo AstBuilder.
fetchStatement
    : FETCH ~SEMI+ SEMI
    ;

closeStatement
    : CLOSE identifier SEMI
    ;

// EXCEPTION sozinho relança o erro do handler. A mensagem é qualquer expressão.
raiseStatement
    : EXCEPTION ( identifier ( USING LPAREN expression ( COMMA expression )* RPAREN | expression )? )? SEMI
    ;

// SUSPEND não é reservada: SUSPEND = ... é atribuição a uma variável com esse nome.
suspendStatement
    : SUSPEND SEMI
    ;

// RETURN valor, só em function.
returnStatement
    : RETURN expression SEMI
    ;

// Fatia DML: texto opaco até o ';'. O AstBuilder localiza os :var, os alvos do INTO e as cláusulas recusadas.
dmlStatement
    : ( SELECT | WITH | INSERT | UPDATE | DELETE | MERGE ) ~SEMI* SEMI
    ;

// Opções e INTO separados pelo AstBuilder, como numa Fatia DML.
executeStatement
    : EXECUTE STATEMENT ~SEMI* SEMI
    ;

// EXECUTE PROCEDURE [pkg.]nome [(]argumentos[)] [RETURNING_VALUES [(]alvos[)]]; separado pelo AstBuilder.
executeProcedure
    : EXECUTE PROCEDURE ~SEMI+ SEMI
    ;

// IN AUTONOMOUS TRANSACTION DO (AUTONOMOUS e TRANSACTION não são reservadas). Reconhecido só para o Pré-check.
autonomousTransaction
    : IN identifier identifier DO statement
    ;

// NEW.campo = ... numa trigger.
assignment
    : COLON? identifier ( DOT identifier )? EQ expression SEMI
    ;

// O ELSE fica com o IF mais próximo (o ANTLR escolhe a alternativa gulosa), como no Firebird.
ifStatement
    : IF LPAREN condition RPAREN THEN statement ( ELSE statement )?
    ;

// O Firebird só aceita label em laço; num BEGIN ... END ele é recusado.
whileStatement
    : label? WHILE LPAREN condition RPAREN DO statement
    ;

label
    : identifier COLON
    ;

// BREAK é o sinônimo antigo de LEAVE, ainda aceito pelo Firebird 5, sem label.
leaveStatement
    : LEAVE identifier? SEMI
    | BREAK SEMI
    ;

continueStatement
    : CONTINUE identifier? SEMI
    ;

exitStatement
    : EXIT SEMI
    ;

// Condição opaca entre os parênteses do IF/WHILE; o servidor aplica a lógica de três valores.
condition
    : expressionContent+
    ;

// Expressão opaca: tokens até ';', ',' ou ')' fora de parênteses.
expression
    : expressionPart+
    ;

expressionPart
    : LPAREN expressionContent* RPAREN
    | ~( SEMI | COMMA | LPAREN | RPAREN )
    ;

expressionContent
    : LPAREN expressionContent* RPAREN
    | ~( LPAREN | RPAREN )
    ;

// Expressão de um watch (RoutineParser.parseWatch): uma só, até o fim do texto.
watchExpression
    : expression EOF
    ;

// Palavras-chave não reservadas do Firebird, que podem ser nome de variável ou label. THEN, ELSE, WHILE, FOR,
// OPEN, FETCH, CLOSE, CURSOR, USING, WHEN, FUNCTION, RETURN e DETERMINISTIC são reservadas. Manter em sincronia
// com AstBuilder.isIdentifier.
identifier
    : IDENTIFIER
    | QUOTED_IDENTIFIER
    | RECREATE
    | BREAK
    | CONTINUE
    | DO
    | EXCEPTION
    | EXIT
    | IF
    | LEAVE
    | STATEMENT
    | SUSPEND
    | BLOCK
    | BODY
    | PACKAGE
    ;
