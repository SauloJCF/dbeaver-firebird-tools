# 02 — Tracer bullet: domínios e generators até o arquivo

Status: ready-for-agent
Blocked by: 01

## O que construir

A fatia mais fina que atravessa todas as camadas.

- Bundle `dev.fbtools.dbeaver.export` + feature própria no zip p2 (instala e desinstala sozinha; ADR 0006).
- Menu **Firebird Tools > Extract Metadata…** e item no menu de contexto da conexão Firebird.
- Wizard mínimo com as abas **Objects** (só Domains e Generators, árvore e lista selecionada) e **Output** (arquivo, nome padrão `<conexão>_<AAAAMMDD-HHMM>.sql`, último diretório lembrado).
- Extração em job de background, com progresso e cancelamento.
- Leitura de `RDB$FIELDS` e `RDB$GENERATORS` direto (ADR 0007), gravação em arquivo com `SET NAMES` do charset padrão do banco (ADR 0009) e `SET GENERATOR`.
- Banco de teste sintético em `samples/export-db/` (script de criação com domínios e generators; as demais fatias o ampliam) e **primeira Referência do IBExpert** para ele em `golden/`.
- Infraestrutura de teste: divisor de script em statements (respeitando `SET TERM`), normalizador e comparador por objeto; arquivo `allowed-diffs` (pode começar vazio ou quase).

## Critérios de aceite

- [x] Extrair domínios e generators do banco sintético gera um script que roda no `isql` num banco vazio sem erro.
- [x] O teste de comparação com a Referência do IBExpert passa, e cada divergência está em `allowed-diffs`.
- [x] Teste de ida e volta dos mesmos objetos verde.
- [x] Cancelar durante a extração remove o arquivo parcial e não deixa job pendurado. (**dispensado por ora pelo usuário**: o apagar do arquivo parcial tem teste automatizado; o cancelamento na view Progress, o job pendurado e a conexão aberta **não foram verificados**, roteiro D)
- [x] Banco `WIN1252` com acentos nos nomes de domínio, nas descrições e nos defaults exporta e recria sem perda.
- [x] A feature instala e desinstala sozinha, sem tirar o depurador (critério 8 da spec). (liberado pelo usuário; roteiro A)

## Dependência humana

- Gerar, no IBExpert, a Referência do banco sintético (Extract Metadata, opções padrão do print) e entregar o `.sql`. Se ele não abrir o banco 5, usar um servidor 2.5 só para essa etapa (ADR 0008).

## Roteiro de teste manual (DBeaver 26.2.2)

Cobre os dois critérios abertos e as conferências que só dá para fazer na tela. Pré-requisitos: o zip de `releng/dev.fbtools.dbeaver.repository/target/` (gerado por `.\mvnw verify`) e um banco Firebird 5 com objetos. Para o banco sintético, rode `samples/export-db/create-export-db.sql` no `isql` apontando o `CREATE DATABASE` para um caminho descartável (nunca o `SAMPLE.FDB`) e crie uma conexão para ele no DBeaver.

**A. Instalar e desinstalar sozinha (critério 6; critério 8 da spec)**

1. Num DBeaver 26.2.2 limpo (sem o depurador), *Help > Install New Software… > Add… > Archive…* e escolha o zip.
2. Marque só **Firebird Metadata Export** (categoria Firebird Tools), conclua e reinicie.
3. Esperado: o menu **Firebird Tools** aparece na barra; o menu **PSQL Debug** não aparece.
4. Instale também **Firebird PSQL Debugger**, reinicie. Esperado: os dois menus.
5. *Help > About > Installation Details*, desinstale só **Firebird Metadata Export**, reinicie. Esperado: **Firebird Tools** some; **PSQL Debug** continua e o depurador funciona (um *Depurar Rotina* simples).

**B. Menu e wizard**

1. Com a conexão Firebird selecionada no navegador: *Firebird Tools > Extract Metadata…* abre o wizard. O mesmo item aparece no menu de contexto (botão direito) da conexão.
2. Com uma conexão que **não** seja Firebird selecionada (ex.: PostgreSQL/SQLite): o item fica desabilitado no menu **Firebird Tools** e ausente no menu de contexto.
3. Aba **Objects**: a árvore mostra *Domains (13)* e *Generators (6)* no banco sintético. *Add >* com itens selecionados, *Add >* com o grupo selecionado (adiciona todos), *< Remove*, *Extract all* e *Clear* atualizam a lista da direita. Com a lista vazia, *Next/Finish* ficam desabilitados.
4. Aba **Output**: nome padrão `<conexão>_<AAAAMMDD-HHMM>.sql`. Troque a pasta, conclua, abra o wizard de novo: a pasta escolhida é a sugerida. Caminho vazio mostra erro e desabilita *Finish*.

**C. Extração e arquivo gerado**

1. *Extract all* + *Finish* com "Abrir no editor ao terminar" marcado. Esperado: um job aparece na view Progress, o arquivo é gravado e abre no editor.
2. O arquivo começa com `SET SQL DIALECT 3;` e `SET NAMES UTF8;`, tem 13 `CREATE DOMAIN`, 6 `CREATE GENERATOR` com `SET GENERATOR` (ex.: `GEN_PEDIDO TO 1000`), os `COMMENT ON` de `D_DINHEIRO` e `GEN_PEDIDO`, e **nenhuma senha**.
3. Extrair só `D_ID` e `GEN_PEDIDO`: o arquivo tem apenas esses dois.
4. Rodar o arquivo gerado num banco vazio (`isql` ou editor SQL do DBeaver, script) termina sem erro.
5. Opção "Codificação do arquivo = UTF-8" num banco `WIN1252`: o arquivo começa com `SET NAMES UTF8;` e os acentos estão corretos. Com "Charset padrão do banco": `SET NAMES WIN1252;`; ao abrir no editor do DBeaver os acentos só aparecem certos se o editor usar a mesma codificação (ADR 0009).
6. Conexão do DBeaver configurada com charset diferente do banco (ex.: UTF8 num banco WIN1252): ao fim aparece um aviso de charset.

**D. Cancelamento (critério 4)**

Precisa de uma extração que dure o bastante para dar tempo de cancelar. No banco sintético ela termina em milissegundos, então use um banco maior (ou crie ~20 mil domínios/generators num banco descartável) ou ponha um breakpoint/pausa no job.

1. Inicie a extração; na view Progress clique em cancelar (X) antes do fim.
2. Esperado: o job termina como cancelado, **o arquivo parcial não existe** na pasta de destino, e não fica nenhum job "Extract Metadata - …" na view Progress.
3. Em outro cliente, `SELECT COUNT(*) FROM MON$ATTACHMENTS` (ou a lista de conexões do servidor): não sobra a conexão "Firebird Metadata Export" aberta.
4. Rode uma nova extração em seguida: funciona normalmente.

Marque os critérios 4 e 6 acima só depois de A e D.

## Comments

### Andamento (agente) — parcial, issue ainda aberto

Feito, sem depender do Firebird 5:

- Bundle `bundles/dev.fbtools.dbeaver.export` (esqueleto, só `package-info.java`), feature `features/dev.fbtools.dbeaver.export.feature` e entrada na `category.xml` ("Firebird Tools"); módulos no pom raiz. `.\mvnw verify -DskipTests` gera o zip com as duas features e os dois plugins.
- Banco sintético `samples/export-db/create-export-db.sql`: subconjunto comum ao Firebird 2.5 e ao 5 inteiro (domínios, generators, exceptions, roles, tabelas, índices, FKs, views, procedures, triggers, descrições, grants) mais dados. Validado no Firebird 5 e no IBExpert. Os objetos só do Firebird 5 (`create-export-db-fb5.sql`) ainda não existem.
- Referência do IBExpert 2012.02.21 em `samples/export-db/golden/export-db.ibexpert.sql` (convertida de Windows-1252 para UTF-8), com `README.md` de origem. O caminho `golden/` não estava definido nos documentos; ficou ao lado do banco sintético.
- Infraestrutura de teste em `bundles/dev.fbtools.dbeaver.export/test/.../testing/`: `ScriptSplitter` (respeita `SET TERM`, comentários e literais), `StatementNormalizer`, `ObjectKey`, `ScriptComparator` e `AllowedDiffs` (formato `TIPO NOME | motivo`, aponta entradas sem uso).
- Teste: `.\mvnw -pl releng/dev.fbtools.dbeaver.target,bundles/dev.fbtools.dbeaver.export verify` — 24 testes, 0 falhas, BUILD SUCCESS. Cobrem o divisor, o normalizador, o comparador e a leitura da Referência (contagem de objetos por tipo).

Pendente:

- Extração (leitura de `RDB$FIELDS` e `RDB$GENERATORS`), wizard, menu, job de background, `SET NAMES`, gravação em arquivo. Nada disso foi escrito.
- Arquivo `allowed-diffs` ainda não existe: só dá para preencher quando houver saída do plugin para comparar.
- Os testes que exigem o Firebird 5 (ida e volta, cancelamento, `WIN1252`) não podem rodar: na máquina só há o Firebird 2.5 (serviço padrão, porta 3050). O `.\mvnw verify` completo também não foi executado, pelo mesmo motivo.
- Diferenças já observadas entre a Referência e o que o plugin deve gerar (candidatas a `allowed-diffs`): `DESCRIBE` no lugar de `COMMENT ON`; `BLOB SUB_TYPE 1/0` no lugar de `TEXT/BINARY`; charset igual ao padrão e `COLLATE` omitidos; tabela `"LOG"` entre aspas; grants de tabelas de sistema (`RDB$FORMATS`, `RDB$PAGES`, `RDB$ROLES`); `GRANT <role> TO <usuário>` ausente.

### Entrega da extração, do wizard e do job (agente)

Feito:

- Núcleo em `dev.fbtools.dbeaver.export.extract` (JDBC puro, sem Eclipse): `MetadataExtractor` lê `RDB$FIELDS` e `RDB$GENERATORS` direto (ADR 0007) e escreve `SET SQL DIALECT 3`, `SET NAMES <charset do banco>`, domínios, generators com `SET GENERATOR` e `COMMENT ON` de domínios e generators (a opção que liga e desliga as descrições é do issue 07). `ExtractionFile` grava no charset do banco (ou UTF-8, com `SET NAMES UTF8`) e apaga o arquivo parcial se a extração falha ou é cancelada. Avisa quando o charset da conexão difere do banco (`MON$ATTACHMENTS`), já que a ADR 0009 pede a conexão no charset do banco e a do DBeaver é a do usuário.
- UI em `dev.fbtools.dbeaver.export.ui`: comando **Extract Metadata…** no menu **Firebird Tools** e no menu de contexto (`popup:org.eclipse.ui.popup.any`, visível só com conexão Firebird), wizard com **Objects** (árvore, Add/Remove/Extract all/Clear, lista de selecionados) e **Output** (arquivo `<conexão>_<AAAAMMDD-HHMM>.sql`, último diretório lembrado, codificação, abrir no editor), job `AbstractJob` com progresso, cancelamento e contexto JDBC isolado sempre fechado.
- `samples/export-db/golden/allowed-diffs` com duas entradas: `COMMENT ON` no lugar de `DESCRIBE` para `D_DINHEIRO` e `GEN_PEDIDO`. As demais diferenças observadas no andamento anterior (BLOB, charset omitido, `"LOG"`, grants) pertencem a tabelas e grants, issues 03 e 07. A comparação só considera descrições de domínios e generators; as das outras entidades ficam para os respectivos issues.
- Testes (`DomainsAndGeneratorsExtractionTest`, 8): conteúdo e cabeçalho, comparação com a Referência, ida e volta, `isql` num banco vazio (assume o `isql` em `C:\Program Files\Firebird\Firebird_5_0`), cancelamento remove o arquivo, seleção parcial, `WIN1252` com acentos em nomes, descrições e defaults (mais coluna `NONE`) com ida e volta, e o aviso de charset da conexão. Helper `ExportTestDatabase` cria bancos `export-<nome>.fdb` ao lado do `database` de `fbdebug-test.properties`.
- Comando: `.\mvnw verify -Dfbdebug.test.config=<properties>` na raiz, com Firebird 5 em localhost:3050. Resultado: BUILD SUCCESS; 231 testes do depurador e 32 da exportação, 0 falhas, 0 ignorados. Gerou o zip p2 com as duas features.

Decisões e pendências:

- Os dois critérios abertos exigem o DBeaver e não foram exercitados (o wizard e o job compilam e resolvem contra o DBeaver 26.2.2, mas ninguém os abriu):
  - **Cancelar sem job pendurado**: o apagar do arquivo parcial está coberto por teste; falta ver no DBeaver. Roteiro: extrair numa conexão Firebird, cancelar na view Progress e conferir que o arquivo sumiu e que a view Progress/`MON$ATTACHMENTS` não ficam com a conexão da exportação.
  - **Instala e desinstala sozinha**: o zip tem as duas features. Falta instalar só a feature da exportação num DBeaver 26.2.2 limpo, ver o menu **Firebird Tools**, desinstalar e confirmar que o **PSQL Debug** continua.
- Da mesma forma, conferir no DBeaver: o item no menu de contexto da conexão no navegador aparece só em conexão Firebird, e abrir o arquivo no editor com `SET NAMES WIN1252` não troca os acentos (ADR 0009).
- A conexão usada é a do DBeaver (isolada), no charset que o usuário configurou; se for diferente do banco, o job mostra o aviso ao fim. Abrir uma conexão própria no charset do banco fica para quando houver a opção de codificação completa.
- Dependência humana (Referência do IBExpert): já entregue no andamento anterior; nenhuma nova é necessária para este issue.

### Dispensa de testes manuais (usuário, 2026-10-31)

O usuário não conseguiu executar os testes manuais de cancelamento (roteiro D) e de codificação no editor (roteiro C, itens 5 e 6) e os dispensou por ora para liberar o issue 03. O critério de cancelamento foi marcado `[x]` por essa dispensa, **sem verificação no DBeaver**; a parte automatizada (arquivo parcial removido, `WIN1252` com ida e volta, aviso de charset) está coberta pelos testes. Reabrir se aparecer job pendurado, conexão sobrando ou acento trocado ao abrir o arquivo no editor. O critério de instalar/desinstalar a feature (roteiro A) continua aberto: não foi dispensado.

Roteiro A (instalar e desinstalar sozinha) liberado pelo usuário em 2026-10-31; critério marcado `[x]`. Com isso todos os critérios do issue 02 estão marcados e o 03 deixa de ser bloqueado.
