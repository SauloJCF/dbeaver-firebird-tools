# 24 — Servidor com `DataTypeCompatibility = 2.5` devolve `BOOLEAN` e `DECFLOAT` com tipo errado

Status: needs-triage
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

## A decidir (humano)

1. **Dedicar uma conexão própria à Sessão de debug** (já existe, por ser dedicada) e aplicar nela `isc_dpb_set_bind` (`dataTypeBind` do Jaybird) com os tipos nativos. Funciona se o driver do DBeaver deixar abrir conexão com propriedades extras; vale confirmar como a conexão dedicada é criada hoje.
2. **Executar `SET BIND OF BOOLEAN TO NATIVE` (e os demais)** na abertura da sessão, em vez de depender da propriedade do driver. Mesmo efeito, sem mexer na conexão.
3. **Não tratar**, e documentar que o depurador exige `DataTypeCompatibility` padrão. Não serve para o ambiente do mantenedor.

Sugestão: 2 (ou 1, se a 2 não valer para o JDBC), com um Teste diferencial rodando também com o banco configurado em `2.5`. Impacto na exportação (`Script de dados`, valores `BOOLEAN` e `DECFLOAT`) deve ser conferido quando o issue 08 da exportação for feito; avisar o `export-data`.

## Critérios de aceite

- [ ] Numa conexão a um servidor com `DataTypeCompatibility = 2.5`, a Sessão de debug mostra `BOOLEAN` como `TRUE`/`FALSE` (sem espaço) e `DECFLOAT` sem `.0`.
- [ ] `IF (C.ATIVO)` e `EXCEPTION ... USING (B, ...)` com `BOOLEAN` dão o mesmo resultado da execução nativa.
- [ ] Os Testes diferenciais passam com a conexão de teste **sem** o `dataTypeBind` explícito, contra um servidor em `2.5` (o contorno em `TestDatabase` é removido ou deixa de ser necessário).
- [ ] README ou `docs/` registram o requisito ou o comportamento quanto a `DataTypeCompatibility`.

## Comments

- Contorno atual só nos testes: ver `.scratch/exportacao-metadata-dados/issues/01-reorganizacao-e-rename.md`, seção "Correção das 3 falhas".
