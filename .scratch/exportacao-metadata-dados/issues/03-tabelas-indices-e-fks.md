# 03 — Tabelas, índices e chaves

Status: ready-for-agent
Blocked by: 02

## O que construir

- Fases de **tabelas sem FKs** e de **índices e FKs** (ADR 0007).
- Colunas com `CHARACTER SET`/`COLLATE`, `DEFAULT`, `NOT NULL`, `COMPUTED BY` (inline, ou separado pela opção), identity, tipos `INT128`, `DECFLOAT`, `TIME ZONE` e `BOOLEAN`.
- Constraints `PRIMARY KEY`, `UNIQUE`, `CHECK`, `FOREIGN KEY` (com `ON DELETE/UPDATE`), índices simples, compostos, descendentes, parciais e computados.
- Global Temporary Tables (`ON COMMIT PRESERVE/DELETE ROWS`).
- `CREATE DATABASE` com `DEFAULT CHARACTER SET` e collation padrão, opcional (opção), sem senha; aviso se existirem colunas `NONE` (ADR 0009).
- Aba **Objects** passa a listar Tables e Indexes; sobem as opções `CHARACTER SET`/`COLLATE` sempre, `COMPUTED BY` separado e Always quote identifiers.
- Banco sintético e Referência do IBExpert ampliados para tabelas (subconjunto comum ao 2.5).

## Critérios de aceite

- [ ] Comparação com a Referência do IBExpert passa para tabelas, índices e FKs, com as diferenças em `allowed-diffs`.
- [ ] Ida e volta verde, incluindo identity, GTT, índices parciais/computados e os tipos novos (sem Referência do IBExpert).
- [ ] Ordem das tabelas independe da ordem alfabética: FKs vão para a Fase seguinte, então ciclos entre tabelas não quebram o script.

## Dependência humana

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments

- 2026-10-31 (ADR 0010, proposta): a Coluna array (dimensões declaradas na coluna ou no domínio, `RDB$FIELD_DIMENSIONS`) tem de sair com as dimensões no Script de metadata (ex.: `INTEGER [1:5]`), ou no mínimo avisar. Nunca emitir como escalar: num banco real isso recria a coluna com tipo errado, em silêncio. A `C_VETOR` saiu do Banco sintético em 2026-10-31, então esse caminho está sem teste (comparação com a Referência do IBExpert e ida e volta) até a ADR 0010 ser aceita e a tabela de arrays voltar. A `C_VETOR` foi retirada à mão da Referência do IBExpert (sem regenerar no IBExpert, ver `golden/README.md`); regenerar quando possível (ADR 0010, Consequências). Não resolver com `allowed-diffs`. Nenhum critério marcado nesta atualização.
