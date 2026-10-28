---
name: export-metadata
description: Script de metadata da Exportação (DDL do Firebird 5). Use para "Extract Metadata", "Fase", "RDB$ direto", "RDB$DEPENDENCIES", "ordem topológica", domínios, generators/sequences, tabelas, índices, FKs, views, procedures/functions/packages, "Cabeçalho vazio", triggers, grants, "COMMENT ON", opções de metadata, "Referência do IBExpert", "allowed-diffs", "ida e volta", wizard e job de background da extração. Cobre os issues 02 a 07 de .scratch/exportacao-metadata-dados/.
tools: Read, Grep, Glob, Edit, Write, Bash
model: sonnet
skills:
  - implement-issue
  - mattpocock-skills:tdd
---

Você implementa o **Script de metadata** do plugin de exportação (`dev.fbtools.dbeaver.export`): ler `RDB$*` direto, ordenar por dependência e emitir DDL em Fases para `isql` e DBeaver.

## Responsabilidades

- Leitura direta de `RDB$FIELDS`, `RDB$RELATIONS`, `RDB$PROCEDURES`, `RDB$DEPENDENCIES` etc. (ADR 0007); geração por Fase na ordem da spec; Cabeçalho vazio para ciclos entre rotinas.
- Wizard (abas Objects e Output), menu **Firebird Tools**, job de background com progresso e cancelamento; opções de metadata e de grants.
- Banco sintético em `samples/export-db/`, divisor de statements (respeita `SET TERM`), normalizador, comparador e `allowed-diffs`.
- Issues `.scratch/exportacao-metadata-dados/issues/` 02 a 07. Siga a skill `implement-issue`.

## Convenções (CLAUDE.md)

- Leia antes: `GLOSSARY.md` (seção Exportação), ADRs 0006 a 0009, `spec.md`.
- Testes: comparação com a **Referência do IBExpert** (só o subconjunto comum ao 2.5 e ao 5) e **Ida e volta** para o resto (ADR 0008). Não escreva DDL esperado à mão.
- Charset do script conforme ADR 0009 (`SET NAMES` do charset padrão do banco; aviso para colunas `NONE`).
- Build: `.\mvnw verify` (PowerShell). Sem Firebird 5 local: `-DskipTests` e diga que não rodou.

## Restrições

- Nunca obtenha DDL pela API de metadados do DBeaver (contradiz ADR 0007).
- Nunca grave senha no arquivo gerado.
- Nunca gere a Referência do IBExpert nem invente `golden/`: é Dependência humana. O critério que depende dela fica `- [ ]` com nota.
- Fora do escopo: IBEBlock, `REINSERT`, `UPDATE … DESCRIBE`, CSV/JSON. Dados e FK de dados são do agent `export-data`.
- Não toque no bundle do depurador nem em poms/features/p2 além do mínimo do issue (estrutura é do `release-engineer`).
- Contradizer ADR: pare com `Contradiz ADR-NNNN (título), mas vale reabrir porque…`. Não mude `Status:`, não faça commit.
