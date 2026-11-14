---
name: implement-issue
description: Implementa um issue do tracker local (.scratch/<feature>/issues/NN-slug.md) de ponta a ponta neste repo. Use quando o usuário pedir "implementa o issue 03", "faz o issue 12", "próximo issue", "continua a exportação / o depurador", "pega o próximo ready-for-agent" ou citar um arquivo de issue. Verifica Status e Blocked by, cria (ou retoma) a branch do issue a partir de develop, lê spec, GLOSSARY.md, ADRs e a referência da área, implementa, roda os testes e fecha o issue (checkboxes e Comments). Não use para criar issues, bugs sem issue, perguntas ou revisão de código.
---

# Implementar um issue do tracker local

Os issues são arquivos markdown em `.scratch/<feature>/issues/NN-slug.md` (ver `docs/agents/issue-tracker.md`). Cada um tem `Status:`, `Blocked by:`, "O que construir" e "Critérios de aceite" com checkboxes.

## Quando NÃO usar

- O usuário quer **criar ou quebrar** issues, ou escrever uma spec.
- Bug ou ajuste sem arquivo de issue: corrija direto, ou use a skill de diagnóstico (a referência da área ainda vale; leia-a).
- Perguntas, explicações, revisão de código (agent `code-reviewer`), ADR ou glossário (agent `architect`).
- O issue está `needs-triage`, `needs-info`, `ready-for-human` ou `wontfix`: não implemente, avise.
- O pedido é só ler ou resumir um issue (agent `issue-keeper`).

## Passos

### 1. Localizar o issue

- Número dado ("issue 03"): procure `NN-*.md` em `.scratch/*/issues/`. Se o número existir em mais de uma feature, pergunte qual.
- "Próximo issue" ou nenhum número: percorra `.scratch/<feature>/issues/` em ordem e pegue o primeiro com `Status: ready-for-agent`, desbloqueado e com algum critério de aceite sem marcar. Se houver mais de uma feature em andamento, pergunte qual.

### 2. Verificar se dá para começar

- `Status:` deve ser `ready-for-agent`. Qualquer outro: pare e diga o motivo.
- `Blocked by: NN, NN`: cada bloqueador precisa ter **todos** os critérios de aceite marcados `[x]`. Se não, pare e diga qual bloqueia.
- Seção "Dependência humana" (ex.: gerar a Referência do IBExpert): ela **não** bloqueia o código, mas o critério que depende dela fica `- [ ]`. Diga isso no fim.

### 3. Criar a branch do issue

Todo o trabalho do issue acontece na branch dele, criada a partir de `develop`; `develop` e `master` só recebem merge.

- Nome: `feature/<apelido>-issue-NN-<slug-curto>`, com apelido `exportacao` (`exportacao-metadata-dados`) ou `depurador` (`fb-psql-debugger`). Ex.: `feature/exportacao-issue-02-extracao`.
- Rode `git status` e `git branch -a`.
- Se já existe uma branch para esse issue (local ou em `origin`), troque para ela com `git switch` e continue de onde parou.
- Se não existe: `git switch develop` e depois `git switch -c feature/<apelido>-issue-NN-<slug-curto>`.
- Se o working tree tem alterações que não são do issue, pare e pergunte antes de trocar de branch; a limpeza é decisão do usuário (nada de `stash`, `reset` ou `checkout --`).
- Não faça push.

### 4. Escolher a área e ler o contexto

Escolha a área pelo que o issue **mexe**, não pelo número. Se tocar duas, leia as duas referências; a primeira da lista é a principal.

| Área | O issue mexe em | Referência |
|---|---|---|
| Interpretador | gramática ANTLR, AST, execução de PSQL, Fatias DML, Avaliação de expressão, Pré-check, Package temporário, Teste diferencial, `TestDatabase` | [references/interpretador.md](references/interpretador.md) |
| Debug UI | launch, modelo de debug do Eclipse, breakpoints, step, Variables, Expressions, menus, ícones, perspectiva, Sessão de debug e sua conexão | [references/debug-ui.md](references/debug-ui.md) |
| Exportação: metadata | DDL lido de `RDB$`, Fases, ordem topológica, Cabeçalho vazio, grants, `COMMENT ON`, wizard e job da extração, comparador e `allowed-diffs` | [references/exportacao-metadata.md](references/exportacao-metadata.md) |
| Exportação: dados | `INSERT`s, `COMMIT WORK`, ordem de FK nos dados, Objetos relacionados, BLOBs, divisão de arquivo, fechamento e docs da exportação | [references/exportacao-dados.md](references/exportacao-dados.md) |
| Build e empacotamento | poms, `MANIFEST.MF` (dependências e exports), `build.properties`, features, target, p2, zip, renomes em massa, JDK/Tycho | [references/build.md](references/build.md) |

Depois, nesta ordem:

1. A `spec.md` da mesma feature.
2. `GLOSSARY.md`. Use os termos como estão definidos (Rotina, Fatia DML, Fase, Cabeçalho vazio, Referência do IBExpert…), na forma que o glossário fixa.
3. As ADRs de `docs/adr/` que a referência da área e o issue citam. Se faltar algum desses arquivos, siga em silêncio.
4. Para código existente, o README (build e testes) e os pacotes vizinhos, para casar com o estilo.

Se a implementação exigir contradizer uma ADR, pare e escreva: `Contradiz ADR-NNNN (título), mas vale reabrir porque…`. A decisão é do usuário (com o agent `architect`).

### 5. Implementar

- Faça só o que está em "O que construir". Trabalho que pertence a outro issue vai como nota em `## Comments`.
- Teste primeiro quando fizer sentido (skill `mattpocock-skills:tdd`), com o tipo de teste que a referência da área manda. Saída e DDL esperados vêm do servidor ou do oráculo, nunca escritos à mão.
- Código e comentários seguem o que já existe no módulo. Texto de issue, README e ADR ficam em português.

### 6. Verificar

- Rode `.\mvnw verify` na raiz (PowerShell). Mudou a gramática ANTLR, renomes ou estrutura: `.\mvnw clean verify`. Para um relatório isolado do log, delegue ao agent `build-verifier`.
- Os testes com banco precisam do Firebird 5 local (`fbdebug-test.properties` na raiz). Sem ele, rode `.\mvnw verify -DskipTests` e **diga que os testes não rodaram**.
- Regras de build (raiz e por inteiro, quando usar `clean`, loopback do Tycho no sandbox e como rodar no terminal do usuário): seção "Build e testes" do `CLAUDE.md`.
- Se os testes falharem, mostre a saída e não marque nada.

### 7. Fechar o issue

No mesmo arquivo do issue:

- Marque `- [x]` só nos critérios realmente verificados. Os que dependem de ação humana ("passo humano", "Dependência humana") ou de teste que não rodou ficam `- [ ]`.
- Acrescente em `## Comments` uma entrada curta com: o que foi feito, o comando de teste e o resultado, decisões tomadas e pendências (inclusive a Dependência humana).
- `Status:` fica como está: o repo usa só os cinco papéis de `docs/agents/triage-labels.md`, e "feito" é indicado pelos checkboxes. Mude só se o usuário pedir.
- **Teste manual do Depurador (só dá para ver no DBeaver):** gere os objetos que exercitam o critério e acrescente-os a `samples/sample-db/create-sample-db.sql` (procedure, function, trigger, exception, tabela, dados). Insira antes de `SET TERM ; ^`, nas seções que já existem, com um comentário curto dizendo o issue e o que conferir. O script continua rodando de ponta a ponta no `isql`, com os objetos existentes intactos. O critério continua `- [ ]`, e o `## Comments` diz qual objeto usar, com quais parâmetros e o resultado esperado.
- Se o issue mudar algo que o README descreve, atualize o README. Termo novo ou definição que mudou no glossário: proponha ao usuário e deixe a edição para o agent `architect`.
- Ao fim, ofereça o agent `code-reviewer` para revisar o diff.
- **Não faça commit** a menos que o usuário peça.

## Exemplos

**Entrada:** "implementa o issue 03"
**Saída esperada:**
1. Acha `.scratch/exportacao-metadata-dados/issues/03-tabelas-indices-e-fks.md`.
2. `Status: ready-for-agent`, `Blocked by: 02`. Confere o 02: se tiver critério aberto, responde "O 03 está bloqueado pelo 02 (falta: …)" e para.
3. Cria `feature/exportacao-issue-03-tabelas` a partir de `develop` (ou troca para ela, se já existir).
4. Área: Exportação: metadata. Lê a referência, a spec, o glossário (Fase, Script de metadata, Ida e volta) e as ADRs 0007, 0008 e 0009.
5. Implementa as Fases de tabelas e índices/FKs com teste de ida e volta e comparação com a Referência.
6. Roda `.\mvnw verify`. Fecha com os checkboxes verificados. O da Referência do IBExpert fica aberto, com a nota "depende da Dependência humana: gerar a nova Referência".

**Entrada:** "próximo issue do depurador"
**Saída esperada:** percorre `.scratch/fb-psql-debugger/issues/`, pega o primeiro `ready-for-agent` desbloqueado com critérios abertos, diz qual escolheu ("Vou fazer o 23 — Connections some na perspectiva Debug", área Debug UI), cria a branch `feature/depurador-issue-23-connections` e segue os passos 4 a 7.

**Entrada:** "cria um issue para exportar triggers"
**Saída esperada:** esta skill **não** se aplica.

**Caso de conflito:** a ADR 0007 manda a exportação ler `RDB$` direto. Se um issue pedisse obter o DDL pela API de metadados do DBeaver, pare e escreva `Contradiz ADR-0007 (exportação lê RDB$ direto), mas vale reabrir porque…`, sem implementar.
