# Área: Exportação, Script de dados

`INSERT`s em ordem de FK, os recursos que ligam dados e metadata (ordem de FK, Objetos relacionados) e o fechamento da feature de exportação.

## Onde

- `INSERT`s em ordem de FK (ordenação via `RDB$DEPENDENCIES`, ADR 0007), leitura em streaming, `COMMIT WORK` a cada N (padrão 500), opções de dados (formatos de data/datetime, trims, BLOBs inline ou em `x'…'`).
- Aba **Data** do wizard, "Add related objects", "Limit file size" (divide em vários arquivos).
- Fechamento: testes consolidados, desempenho no banco de referência (~380 tabelas, ~590 views) e docs (README e `docs/`).
- Ler antes: `GLOSSARY.md` (Script de dados, Charset do script, Ida e volta), ADRs 0007 a 0009.

## Regras do domínio

- **Streaming sempre**: a tabela é lida em fluxo, e a extração é cancelável sem travar a UI.
- **Charset do script** (ADR 0009): acentos preservados em `WIN1252`/`NONE`.
- **Senha nunca vai para o arquivo gerado.**
- Reuse as Fases da área Exportação: metadata para o DDL.
- Fora do escopo: CSV/JSON (Data Transfer nativo do DBeaver), IBEBlock.

## Testes

Prove com **Ida e volta**: extrai, roda num banco vazio, extrai de novo e compara scripts e conteúdo das tabelas.
