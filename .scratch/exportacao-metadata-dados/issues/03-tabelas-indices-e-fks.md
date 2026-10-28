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
