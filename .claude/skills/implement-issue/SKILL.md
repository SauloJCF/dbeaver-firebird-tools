---
name: implement-issue
description: Implementa um issue do tracker local (.scratch/<feature>/issues/NN-slug.md) de ponta a ponta neste repo. Use quando o usuário pedir "implementa o issue 03", "faz o issue 12", "próximo issue", "continua a exportação / o depurador", "pega o próximo ready-for-agent" ou citar um arquivo de issue. Verifica Status e Blocked by, lê spec, GLOSSARY.md e ADRs da área, implementa, roda os testes e fecha o issue (checkboxes e Comments). Não use para criar issues, bugs sem issue, perguntas ou revisão de código.
---

# Implementar um issue do tracker local

Os issues são arquivos markdown em `.scratch/<feature>/issues/NN-slug.md` (ver `docs/agents/issue-tracker.md`). Cada um tem `Status:`, `Blocked by:`, "O que construir" e "Critérios de aceite" com checkboxes.

## Quando NÃO usar

- O usuário quer **criar ou quebrar** issues, ou escrever uma spec.
- Bug ou ajuste sem arquivo de issue: corrija direto, ou use a skill de diagnóstico.
- Perguntas, explicações, revisão de código, ADR ou glossário.
- O issue está `needs-triage`, `needs-info`, `ready-for-human` ou `wontfix`: não implemente, avise.
- O pedido é só ler ou resumir um issue.

## Passos

### 1. Localizar o issue

- Número dado ("issue 03"): procure `NN-*.md` em `.scratch/*/issues/`. Se o número existir em mais de uma feature, pergunte qual.
- "Próximo issue" ou nenhum número: percorra `.scratch/<feature>/issues/` em ordem e pegue o primeiro com `Status: ready-for-agent`, desbloqueado e com algum critério de aceite sem marcar. Se houver mais de uma feature em andamento, pergunte qual.

### 2. Verificar se dá para começar

- `Status:` deve ser `ready-for-agent`. Qualquer outro: pare e diga o motivo.
- `Blocked by: NN, NN`: cada bloqueador precisa ter **todos** os critérios de aceite marcados `[x]`. Se não, pare e diga qual bloqueia. Não implemente na frente.
- Seção "Dependência humana" (ex.: gerar a Referência do IBExpert): ela **não** bloqueia o código, mas o critério que depende dela não pode ser marcado. Diga isso no fim.

### 3. Ler o contexto, nesta ordem

1. A `spec.md` da mesma feature.
2. `GLOSSARY.md`. Use os termos como estão definidos (Rotina, Fatia DML, Fase, Cabeçalho vazio, Referência do IBExpert…). Não use os sinônimos que o glossário manda evitar.
3. As ADRs de `docs/adr/` que tocam a área do issue. Se o issue citar números de ADR, leia esses. Se faltar algum desses arquivos, siga em silêncio.
4. Para código existente, o README (build e testes) e os pacotes vizinhos, para casar com o estilo.

Se a implementação exigir contradizer uma ADR, **não sobrescreva em silêncio**. Pare e escreva: `Contradiz ADR-NNNN (título), mas vale reabrir porque…`.

### 4. Implementar

- Faça só o que está em "O que construir". Se perceber trabalho que pertence a outro issue, anote em `## Comments` e deixe de fora.
- Teste primeiro quando fizer sentido (skill `mattpocock-skills:tdd`). Para o depurador, o teste certo é o **Teste diferencial** (ADR 0005). Para a exportação, a **comparação com a Referência do IBExpert** e a **ida e volta** (ADR 0008). Não escreva DDL esperado à mão.
- Código e comentários seguem o que já existe no módulo. Texto de issue, README e ADR ficam em português.

### 5. Verificar

- Rode `.\mvnw verify` na raiz (PowerShell). Se mudou a gramática ANTLR, use `.\mvnw clean verify`.
- Os testes diferenciais precisam do Firebird 5 local (`fbdebug-test.properties`). Sem ele, rode `.\mvnw verify -DskipTests` e **diga que os testes não rodaram**. Não marque como verde o que não foi executado.
- Se os testes falharem, mostre a saída. Não marque nada.

### 6. Fechar o issue

No mesmo arquivo do issue:

- Marque `- [x]` só nos critérios realmente verificados. Os que dependem de ação humana ou de teste que não rodou ficam `- [ ]`.
- Acrescente em `## Comments` uma entrada curta com: o que foi feito, o comando de teste e o resultado, decisões tomadas e pendências (inclusive a Dependência humana).
- **Não crie** `Status:` novo. O repo usa só os cinco papéis de `docs/agents/triage-labels.md`, e "feito" é indicado pelos checkboxes. Só mude o `Status:` se o usuário pedir.
- Se o issue mudar algo que o README ou o glossário descrevem, atualize esses arquivos.
- **Não faça commit** a menos que o usuário peça.

## Exemplos

**Entrada:** "implementa o issue 03"
**Saída esperada:**
1. Acha `.scratch/exportacao-metadata-dados/issues/03-tabelas-indices-e-fks.md`.
2. `Status: ready-for-agent`, `Blocked by: 02`. Confere o 02: se tiver critério aberto, responde "O 03 está bloqueado pelo 02 (falta: …)" e para.
3. Lê a spec, o glossário (Fase, Script de metadata, Ida e volta) e as ADRs 0007, 0008 e 0009.
4. Implementa as Fases de tabelas e índices/FKs com teste de ida e volta e comparação com a Referência.
5. Roda `.\mvnw verify`. Fecha com os checkboxes verificados. O da Referência do IBExpert fica aberto, com a nota "depende da Dependência humana: gerar a nova Referência".

**Entrada:** "próximo issue do depurador"
**Saída esperada:** percorre `.scratch/fb-psql-debugger/issues/`, pega o primeiro `ready-for-agent` desbloqueado com critérios abertos, diz qual escolheu ("Vou fazer o 23 — Connections some na perspectiva Debug") e segue os passos 3 a 6.

**Entrada:** "cria um issue para exportar triggers"
**Saída esperada:** esta skill **não** se aplica. Não a use.

**Caso de conflito:** a ADR 0007 manda a exportação ler `RDB$` direto. Se um issue pedisse obter o DDL por outro caminho (por exemplo, a API de metadados do DBeaver), pare e escreva `Contradiz ADR-0007 (exportação lê RDB$ direto), mas vale reabrir porque…`, sem implementar.
