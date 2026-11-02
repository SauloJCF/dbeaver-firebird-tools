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

### Nota da issue 05 (agente, 2026-11-01)

- Exceptions precisam sair **antes** dos corpos das rotinas (o corpo de `SP_FECHAR_PEDIDO` usa `E_PEDIDO_FECHADO`). Ao implementá-las, remover `ExportTestDatabase.createSyntheticExceptions()` e as chamadas em `DomainsAndGeneratorsExtractionTest`, `TablesExtractionTest`, `ViewsExtractionTest` e `RoutinesExtractionTest`.
- Triggers que leem views ou chamam rotinas devem sair depois do bloco `Routine bodies` / `Views that read routines`.
- Desvio herdado da 02: `SET GENERATOR` sai junto de cada `CREATE GENERATOR`, mas a ADR 0007 e a spec o põem numa Fase própria, depois dos triggers. Mover aqui ou registrar a decisão de manter.
