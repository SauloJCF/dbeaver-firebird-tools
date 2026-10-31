# 24 — Servidor com `DataTypeCompatibility = 2.5` devolve `BOOLEAN` e `DECFLOAT` com tipo errado

Status: ready-for-agent
Blocked by: —

Spec: `../spec.md`

## O que acontece

O ambiente de uso é Firebird 5.0 com `DataTypeCompatibility = 2.5` no `firebird.conf` (e o Firebird local de desenvolvimento do mantenedor tem uma configuração antiga equivalente). Nesse modo o servidor devolve ao cliente:

- `BOOLEAN` como `CHAR(5)`: `TRUE ` (com espaço no fim) e `FALSE`;
- `DECFLOAT` como `DOUBLE PRECISION`: `1000.0` em vez de `1000`;
- provavelmente também `INT128` como `NUMERIC`/`DOUBLE`, e `TIME WITH TIME ZONE`/`TIMESTAMP WITH TIME ZONE` como `TIME`/`TIMESTAMP` sem fuso.

Foi descoberto ao rodar a suíte: 3 testes falhavam (`ValueTextTest`, `ExceptionDifferentialTest.userExceptionArgumentsAreFormattedByTheServer`, `CursorDifferentialTest.cursorFieldsInExpressionsConditionsAndDml`). Nos testes foi contornado em `TestDatabase.connect()` com a propriedade de conexão `dataTypeBind=boolean to native;decfloat to native;int128 to native;time zone to native`. **O plugin em si não trata isso**, porque a Sessão de debug usa a conexão do DBeaver, sem essa propriedade.

## Impacto esperado no plugin

- **Variables e Console:** `BOOLEAN` aparece como `TRUE ` (texto), e `DECFLOAT` como `double`. A edição de variável converte o texto de volta por `CAST`, o que pode divergir.
- **Argumentos de `EXCEPTION ... USING`:** o Interpretador manda o `BOOLEAN` como `TRUE ` e a mensagem sai com espaço a mais, diferente da execução nativa.
- **Cursores e condições:** `IF (C.ATIVO)` com a coluna lida como `CHAR(5)` dá `SQL error -104, invalid usage of boolean expression` na Avaliação de expressão.
- **Teste diferencial:** a comparação nativo × Interpretador pode divergir de modo que só aparece com essa configuração.

## Decisão (triagem)

Responsável principal: `debug-ui` (o `psql-interpreter` cobre `TestDatabase`).

- **Abordagem:** executar `SET BIND OF … TO NATIVE` na abertura da Sessão de debug, num ponto único (`RoutineLauncher.connection()` ou equivalente). Descartadas: conexão própria com `dataTypeBind` (obrigaria sair de `openIsolatedContext` e reimplementar credenciais, SSL e túnel) e não tratar (não serve para o ambiente do mantenedor).
- **Tipos:** `BOOLEAN`, `DECFLOAT`, `INT128` e `TIME ZONE`, o mesmo conjunto do contorno atual dos testes.
- **Falha do `SET BIND`:** abortar o launch com a mensagem do servidor. Não depurar com tipos errados em silêncio.
- **Testes:** remover o `dataTypeBind` de `TestDatabase.connect()`. O `SET BIND` fica num ponto compartilhado, que a Sessão e a conexão de referência do Teste diferencial usam. A suíte roda só contra o servidor local em `2.5`; um segundo servidor em `DataTypeCompatibility` padrão fica como melhoria futura.
- **Fora do escopo:** Exportação (Script de dados). Nota deixada no `## Comments` do issue 08 da exportação, para o `export-data`.

## Critérios de aceite

- [x] Numa conexão a um servidor com `DataTypeCompatibility = 2.5`, a Sessão de debug mostra `BOOLEAN` como `TRUE`/`FALSE` (sem espaço) e `DECFLOAT` sem `.0`.
- [x] `IF (C.ATIVO)` e `EXCEPTION ... USING (B, ...)` com `BOOLEAN` dão o mesmo resultado da execução nativa.
- [x] Os Testes diferenciais passam com a conexão de teste **sem** o `dataTypeBind` explícito, contra um servidor em `2.5` (o contorno em `TestDatabase` é removido ou deixa de ser necessário).
- [x] Se o `SET BIND` falhar na abertura da Sessão, o launch é abortado com a mensagem do servidor, sem iniciar o depurador.
- [x] README ou `docs/` registram o requisito ou o comportamento quanto a `DataTypeCompatibility`.

## Como testar à mão (passo humano)

Fecha os critérios 1 e 4. O objeto de teste é `SP_TIPOS_NATIVOS(P_ATIVO BOOLEAN, P_LANCA BOOLEAN)`, com a exceção `E_COM_BOOLEAN`, em `samples/sample-db/create-sample-db.sql`. Ela tem uma variável `BOOLEAN`, um `DECFLOAT`, um `IF` sobre `BOOLEAN`, um `FOR SELECT TRUE` com condição sobre a coluna lida e um `EXCEPTION ... USING` com `BOOLEAN`.

1. **Servidor em 2.5 só para o sample.** No `databases.conf` do Firebird 5 (aberto como administrador), acrescente o alias abaixo e reinicie o serviço:
   ```
   sample25 = C:\development\projects\dbeaver-firebird-tools\samples\sample-db\SAMPLE.FDB
   {
       DataTypeCompatibility = 2.5
   }
   ```
2. **Banco.** Ajuste o caminho do `CREATE DATABASE` (linha 4 do script) para o mesmo do alias. Se já existir um `SAMPLE.FDB` lá, renomeie-o. Na pasta `samples\sample-db`: `isql -user SYSDBA -password masterkey -i create-sample-db.sql`.
3. **Conexão e conferência.** No DBeaver com o plugin, crie uma conexão Firebird em `localhost/3050`, banco `sample25`. Rode `SELECT TRUE FROM RDB$DATABASE`: o cabeçalho da coluna deve ter o ícone **A-Z** e o tooltip `CHAR`. Se vier `BOOLEAN`, o alias não pegou e o teste não vale.
4. **Referência nativa.** No editor SQL, rode `SELECT * FROM SP_TIPOS_NATIVOS(TRUE, FALSE)` e `EXECUTE PROCEDURE SP_TIPOS_NATIVOS(TRUE, TRUE)`. Anote o `R_MSG` e a mensagem do erro (`Ativo: TRUE`).
5. **Critério 1.** Abra `SP_TIPOS_NATIVOS` na pasta **Source**, ponha um breakpoint em `V_ATIVO = P_ATIVO;` e use **PSQL Debug > Depurar Rotina** com `P_ATIVO = TRUE` e `P_LANCA = FALSE`. Avance com F6 olhando a view Variables: `V_ATIVO`, `R_ATIVO` e `V_COLUNA` devem ser `TRUE` (sem espaço no fim), `R_VALOR` deve ser `1000` (sem `.0`), o `IF (V_ATIVO)` e o `IF (V_COLUNA)` não podem dar `-104, invalid usage of boolean expression`, e `R_MSG` deve acabar `ativo / coluna ativa`. Com F8 a sessão termina, e o Console mostra o mesmo resultado da referência.
6. **Exceção.** Repita com `P_LANCA = TRUE` e F8 até o fim: a mensagem do Console tem que ser igual à do passo 4, sem espaço extra.
7. **Critério 4.** Em `NativeTypes.STATEMENTS`, troque um comando por `SET BIND OF NAO_EXISTE TO NATIVE` e gere o zip com `.\mvnw clean verify -DskipTests` (sem o `-DskipTests` o build falha, porque `TestDatabase.connect()` usa o mesmo `NativeTypes.bind` e todos os testes com banco quebram com `Token unknown ... NAO_EXISTE`), reinstale e dê o Launch: ele deve ser abortado com o erro do servidor, sem abrir a perspectiva Debug. Desfaça a troca.

Contraprova opcional: comentar `NativeTypes.bind(connection)` em `RoutineLauncher.connection()` deve trazer de volta `TRUE `, `1000.0` e o erro `-104` no passo 5. Restaure a linha.

Se o diálogo de launch recusar `TRUE` num parâmetro `BOOLEAN`, anote em `## Comments`: é entrada de parâmetro, de outro escopo (issue 22).

## Comments

- Contorno atual só nos testes: ver `.scratch/exportacao-metadata-dados/issues/01-reorganizacao-e-rename.md`, seção "Correção das 3 falhas".
- Implementado: `interp/NativeTypes.bind(Connection)` executa `SET BIND OF BOOLEAN|DECFLOAT|INT128|TIME ZONE TO NATIVE`. `RoutineLauncher.connection()` o chama logo após `setAutoCommit(true)`, dentro do `prepare`, então uma falha sobe como `InvocationTargetException` e o launch não começa. `TestDatabase.connect()` perdeu o `dataTypeBind` e usa o mesmo `NativeTypes.bind`; `connectAsIs()` devolve a conexão sem ajuste. Teste novo: `NativeTypesTest` (3 casos). O README ganhou a seção "`DataTypeCompatibility` do servidor".
- `.\mvnw verify`: BUILD SUCCESS, 231 testes, 0 falhas, 0 ignorados.
- Teste manual: ver "Como testar à mão". Objetos de teste (`SP_TIPOS_NATIVOS`, `E_COM_BOOLEAN`) já estão em `samples/sample-db/create-sample-db.sql`.
- 2026-10-30, teste manual (passo humano) pelo mantenedor: passos 1 a 7 de "Como testar à mão" deram o esperado contra o sample em `DataTypeCompatibility = 2.5` (critérios 1 e 4). A contraprova opcional não foi feita. A troca do `SET BIND` inválido em `NativeTypes` foi desfeita. Com ela, o `.\mvnw verify` falha em todos os testes com banco; o zip precisa de `-DskipTests`.
