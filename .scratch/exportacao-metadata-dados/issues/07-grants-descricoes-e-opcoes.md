# 07 — Grants, descrições e opções de metadata

Status: ready-for-agent
Blocked by: 06

## O que construir

- Fase de **descrições** (`COMMENT ON`, sempre) e de **grants** (`GRANT … TO`, incluindo roles, `WITH GRANT OPTION`, `GRANT ROLE`, privilégios por coluna e execução de rotinas).
- Opções: Set Generators, incluir descrições, Decode domains, `CREATE OR ALTER`, não usar `SET TERM`, Always quote identifiers, `CHARACTER SET`/`COLLATE` sempre, extrair privilégios, só dos objetos selecionados.
- Aba **Options** completa (grupos Geral, Metadata, Grants), com valores padrão como nos prints do IBExpert, e lembrados entre usos.
- Opções do IBExpert deliberadamente fora: IBEBlock, Exclude IBE$/TMP$, `UPDATE … DESCRIBE`, `COMMENT` opcional.

## Critérios de aceite

- [ ] Cada opção tem teste que liga e desliga e confere o efeito no script.
- [ ] Comparação com a Referência do IBExpert para grants e descrições passa; `COMMENT ON` consta em `allowed-diffs` como diferença aceita.
- [ ] Ida e volta verde com grants restritos aos objetos selecionados.

## Dependência humana

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments
