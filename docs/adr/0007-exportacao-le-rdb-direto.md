# 0007 — A exportação lê as tabelas `RDB$*` direto

Status: aceito — 2026-10-28

## Contexto

O plugin de exportação gera um script de metadata do banco inteiro (e um de dados) no estilo do IBExpert, com os objetos do Firebird 5. O DBeaver tem modelo de metadados e geradores de DDL para o driver de Firebird, mas cobrem pouco do Firebird 5 (packages, identity, publications, mappings, `DECFLOAT`, `INT128`…) e não dão controle sobre ordem de dependência, `SET TERM`, cabeçalhos vazios de rotinas e formatação.

## Decisão

- O plugin consulta `RDB$RELATIONS`, `RDB$RELATION_FIELDS`, `RDB$DEPENDENCIES`, `RDB$PROCEDURES`, `RDB$FUNCTIONS`, `RDB$PACKAGES` e as demais tabelas de sistema pelo JDBC, e monta o DDL por conta própria. É o mesmo caminho do `DatabaseRoutineCatalog` do depurador.
- O script sai em fases ordenadas por `RDB$DEPENDENCIES`: tipos e domínios, generators/sequences, exceptions, UDFs, collations, tabelas sem FKs, views em ordem topológica, rotinas/functions/packages como **cabeçalho vazio**, índices e FKs, corpos das rotinas (`ALTER`/`CREATE OR ALTER`), triggers, `SET GENERATOR`, descrições, grants. Os cabeçalhos vazios resolvem os ciclos entre rotinas.
- O alvo é o `isql` e o DBeaver: `SET TERM` por padrão. A senha **nunca** entra no arquivo.
- Cobre os tipos do IBExpert mais os do Firebird 5 (Functions, Packages, Sequences, Collations, BLOB Filters, Indexes, triggers de banco e DDL, GTTs, identity, Publications, Mappings, Grants). Descartados: aba IBEBlock, `REINSERT`, "Exclude IBE$/TMP$", `UPDATE ... DESCRIBE` e `COMMENT` opcional (a saída é sempre `COMMENT ON`).
- A extração roda em job de background, com progresso e cancelamento; os dados são lidos em streaming e gravados direto no arquivo.

## Alternativas descartadas

- **Geradores de DDL do DBeaver**: perda de controle sobre ordem e cobertura do Firebird 5.
- **Chamar o `isql -x`/`gbak -m` do Firebird**: dependência de binários externos e menos controle sobre opções e filtragem de objetos.

## Consequências

- Os testes de DDL só são confiáveis contra um Firebird 5 real (ADR 0008).
- A cada versão nova do Firebird, as consultas de catálogo precisam ser revistas.
