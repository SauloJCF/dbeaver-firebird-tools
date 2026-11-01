# Área: Exportação, Script de metadata

DDL do Firebird 5 lido de `RDB$*`, ordenado por dependência e emitido em Fases para `isql` e DBeaver, no bundle `dev.fbtools.dbeaver.export`.

## Onde

- Leitura direta de `RDB$FIELDS`, `RDB$RELATIONS`, `RDB$PROCEDURES`, `RDB$DEPENDENCIES` etc.; geração por Fase na ordem da spec; Cabeçalho vazio para ciclos entre rotinas.
- Wizard (abas Objects e Output), menu **Firebird Tools**, job de background com progresso e cancelamento; opções de metadata e de grants.
- Banco sintético em `samples/export-db/`, divisor de statements (respeita `SET TERM`), normalizador, comparador e `allowed-diffs`.
- Ler antes: `GLOSSARY.md` (seção Exportação), ADRs 0006 a 0009.

## Regras do domínio

- **DDL vem de `RDB$` direto** (ADR 0007), nunca da API de metadados do DBeaver.
- **Charset do script** conforme ADR 0009: `SET NAMES` do charset padrão do banco; aviso para colunas `NONE`.
- **Senha nunca vai para o arquivo gerado.**
- Fora do escopo: IBEBlock, `REINSERT`, `UPDATE … DESCRIBE`, CSV/JSON.

## Testes

Comparação com a **Referência do IBExpert** (só o subconjunto comum ao 2.5 e ao 5) e **Ida e volta** para o resto (ADR 0008). O DDL esperado vem do oráculo. Gerar a Referência e o `golden/` é Dependência humana: o critério que depende dela fica `- [ ]` com nota.

## Fronteiras

`INSERT`s e ordem de FK dos dados são da área Exportação: dados. Bundle do depurador, poms, features e p2 além do mínimo do issue são da área Build.
