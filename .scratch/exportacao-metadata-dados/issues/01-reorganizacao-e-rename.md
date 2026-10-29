# 01 — Reorganização em múltiplos plugins e rename

Status: ready-for-agent
Blocked by: —

## O que construir

Implementar a ADR 0006, sem mudar nenhum comportamento do depurador.

- Renomear o prefixo `dev.fbdebug.dbeaver` para `dev.fbtools.dbeaver`, e o bundle do depurador para `dev.fbtools.dbeaver.psqldebug`: diretório em `bundles/`, pacotes Java (`dev.fbtools.dbeaver.psqldebug.*`), `MANIFEST.MF` (`Bundle-SymbolicName`), `plugin.xml` (ids de comandos, launch config type, perspectiva, extension points), `pom.xml`, `build.properties`, testes.
- Mesma renomeação para a feature (`features/dev.fbtools.dbeaver.psqldebug.feature`), o target (`releng/dev.fbtools.dbeaver.target`) e o repositório p2 (`releng/dev.fbtools.dbeaver.repository`), com o zip chamado `firebird-tools-<versão>.zip` e a `category.xml` com uma categoria "Firebird Tools".
- `groupId` e `artifactId` do pom raiz: `dev.fbtools.dbeaver:fbtools-parent`.
- O `org.eclipse.debug.ui` empacotado fica **só** na feature do depurador.
- Deixar o pom raiz, o target e o repositório prontos para receber um segundo bundle e uma segunda feature (sem criar o plugin da exportação ainda).
- Renomear o repositório Git/remoto para `dbeaver-firebird-tools` (passo humano) e ajustar README, `CLAUDE.md`, `docs/agents/*`, `NOTICE` e o `.launch` do target.
- README vira um índice dos plugins; a instalação detalhada do depurador vai para `docs/instalacao-psqldebug.md` (imagens continuam em `docs/images/`).
- Ajustar a referência a `fbdebug-test.properties` somente se o nome mudar; manter o arquivo atual funcionando.

## Critérios de aceite

- [x] `.\mvnw clean verify` termina em `BUILD SUCCESS` com a mesma contagem de testes verdes de antes (228, contra o Firebird 5 local).
- [x] O zip tem a feature `...psqldebug.feature`, o plugin renomeado e o `org.eclipse.debug.ui`.
- [x] Nenhuma ocorrência de `dev.fbdebug` fora de `docs/adr/` (ADRs antigas, que são histórico) e deste diretório `.scratch`.
- [ ] Instalação limpa do novo zip num DBeaver 26.2.2 mostra o menu **PSQL Debug** como antes. (passo humano)
- [x] ADR 0004 recebe uma nota ("renomeado na ADR 0006") sem reescrever a decisão.
- [x] `GLOSSARY.md` e `CLAUDE.md` continuam coerentes (contexto único).

## Comments

### Implementação (agente)

- Bundle `bundles/dev.fbtools.dbeaver.psqldebug` (pacotes `dev.fbtools.dbeaver.psqldebug.*`, ids do `plugin.xml`, `Bundle-SymbolicName`), feature `features/dev.fbtools.dbeaver.psqldebug.feature`, `releng/dev.fbtools.dbeaver.target` e `releng/dev.fbtools.dbeaver.repository`; pom raiz `dev.fbtools.dbeaver:fbtools-parent`. O repositório e o pom raiz já estão prontos para um segundo módulo (basta acrescentar `<module>` e a feature em `category.xml`).
- Zip: `firebird-tools-<versão>.zip`, com a feature `...psqldebug.feature`, o plugin renomeado e o `org.eclipse.debug.ui`. `category.xml` com a categoria "Firebird Tools". O `org.eclipse.debug.ui` fica só na feature do depurador.
- README virou índice dos plugins, mais a seção de desenvolvimento; instalação e uso do depurador foram para `docs/instalacao-psqldebug.md` (imagens em `docs/images/`). `NOTICE`, ADR 0004 (nota "renomeado na ADR 0006"), agents do `.claude/` e `.opencode/` ajustados. O `.launch` do target virou `DBeaver com Firebird Tools.launch` e usa o workspace `runtime-dbeaver-fbtools` (o `runtime-dbeaver-fbdebug` antigo continua em disco, com as conexões; copie ou recrie a conexão).
- `fbdebug-test.properties` (e `.example`, e a propriedade `fbdebug.test.config`) mantidos como estão, como o issue permite.
- `git grep "dev.fbdebug"` fora de `docs/adr/` e `.scratch/`: nenhuma ocorrência.
- Teste: `.\mvnw clean verify`. **BUILD FAILURE**: 228 testes, 3 falhas (a contagem bate com a de antes). Rodei o mesmo comando no HEAD anterior, sem as mudanças, e as mesmas 3 falharam, então não vêm do rename:
  - `ValueTextTest.valuesAreShownReadablyAndConvertBackToTheSameValue` (esperado `1000`, veio `1000.0`; `TRUE` veio com espaço à direita);
  - `CursorDifferentialTest.cursorFieldsInExpressionsConditionsAndDml` (SQL error -104, uso inválido de expressão booleana no Interpretador);
  - `ExceptionDifferentialTest.userExceptionArgumentsAreFormattedByTheServer` (`TRUE ` com espaço no argumento da exceção).
  Parecem ligadas ao Firebird/Jaybird local (booleano em CHAR, `NUMERIC`). Com `-Dmaven.test.failure.ignore=true` o empacotamento termina em BUILD SUCCESS e o zip foi conferido. Por isso o primeiro critério fica aberto.

### Pendências

- Critério 1: resolvido, ver abaixo.
- Critério 4 (humano): instalar o novo zip num DBeaver 26.2.2 limpo e conferir o menu **PSQL Debug**.
- Passo humano de renomear o repositório no GitHub: o `origin` já aponta para `dbeaver-firebird-tools`.

### Correção das 3 falhas

- Causa: o Firebird local tem `DataTypeCompatibility` antigo, e então o Jaybird recebia `BOOLEAN` como `CHAR(5)` (`TRUE `) e `DECFLOAT` como `DOUBLE` (`1000.0`). Não era o rename, e as mesmas 3 falhavam antes dele.
- Correção: `TestDatabase.connect()` passa `dataTypeBind=boolean to native;decfloat to native;int128 to native;time zone to native`. Só os testes mudaram.
- `.\mvnw clean verify`: 228 testes, 0 falhas, `BUILD SUCCESS`.
- Atenção: conexões do DBeaver a um servidor com essa configuração terão o mesmo comportamento em `BOOLEAN`/`DECFLOAT`. O plugin não trata isso, e está fora do escopo deste issue.
