# 06 — Triggers e demais objetos

Status: ready-for-agent
Blocked by: 05

## O que construir

- **Triggers** de tabela (`BEFORE/AFTER`, `INSERT/UPDATE/DELETE`, posição, ativa/inativa), de banco (`ON CONNECT`, etc.) e DDL.
- **Exceptions**, **UDFs** (só se existirem; `DECLARE EXTERNAL FUNCTION`), **Collations**, **BLOB Filters**, **Roles**.
- **Sequences** com `START WITH` e `INCREMENT`, opção `SEQUENCE` no lugar de `GENERATOR`.
- **Publications** e **Mappings**.
- Aba **Objects** completa, com todos os tipos do spec.
- Banco sintético ampliado.

## Critérios de aceite

- [ ] Comparação com a Referência do IBExpert passa para o subconjunto comum (triggers de tabela, exceptions, UDFs, roles).
- [ ] Ida e volta verde para os tipos exclusivos do Firebird 5 (sequence com `INCREMENT`, triggers de banco e DDL, publications, mappings, collations).
- [ ] Triggers saem depois dos corpos das rotinas que chamam (Fase correta).

## Dependência humana

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments
