# Spec — Exportação de metadata e dados (Firebird 5)

Status: needs-triage

Vocabulário: ver `GLOSSARY.md` (seção "Exportação de metadata e dados"). Decisões: `docs/adr/0006` a `0009`.

## Objetivo

Extrair do DBeaver CE, para um arquivo `.sql`, o metadata de um banco Firebird 5 e os dados de tabelas escolhidas, com os mesmos recursos do "Extract Metadata" do IBExpert (a versão antiga, compatível com o Firebird 2.5) mais os objetos do Firebird 5. Não precisa ser idêntico ao IBExpert.

## Ambiente alvo

- DBeaver CE **26.2.2** e Firebird **5.0** (ADR 0006). Plugin `dev.fbtools.dbeaver.export`, feature própria no mesmo zip p2 do depurador.
- Referência de uso: banco com ~250 domínios, ~380 tabelas, ~590 views, ~400 procedures, ~320 triggers, ~260 generators. Desempenho e cancelamento importam.

## Escopo

### Tipos de objeto

- Do IBExpert: Domains, Tables, Views, Procedures, Triggers, Generators, Exceptions, UDFs (só se existirem), Roles.
- Do Firebird 5: Functions, Packages (header e body), Sequences (`START WITH`/`INCREMENT`), Collations, BLOB Filters, Indexes (incl. parciais e computados), triggers de banco e DDL, Global Temporary Tables, colunas identity, Publications, Mappings, Grants. Tipos `INT128`, `DECFLOAT`, `TIME ZONE` e `BOOLEAN` em colunas e domínios.
- Rotinas UDR saem como `EXTERNAL NAME`.

### Interface

- Wizard com abas **Objects**, **Data**, **Options** e **Output**, aberto pelo menu de contexto da conexão e por um menu **Firebird Tools**.
- Objects: árvore de disponíveis e lista de selecionados, "Extract all", **Add related objects**.
- Data: escolha das tabelas cujos dados serão exportados.
- Output: arquivo, "Limit file size" (divide em vários), abrir no editor SQL ao terminar, progresso e cancelamento (job de background).
- Nome padrão do arquivo `<conexão>_<AAAAMMDD-HHMM>.sql`; último diretório lembrado.

### Opções

- **Gerais**: gerar `CREATE DATABASE` / `CONNECT` (sem senha), limite de tamanho do arquivo, codificação (padrão: charset do banco, UTF-8 como alternativa).
- **Metadata**: Set Generators, incluir descrições (`COMMENT ON`), `COMPUTED BY` separado, `CHARACTER SET` / `COLLATE` sempre, Decode domains, `CREATE OR ALTER` para procedures/triggers/views, não usar `SET TERM`, `SEQUENCE` no lugar de `GENERATOR`, Always quote identifiers.
- **Dados**: formato de data e de datetime, prefixo ANSI, remover espaços finais e caracteres de controle, extrair BLOBs (texto inline, binário em `x'…'`), `COMMIT WORK` a cada N registros (padrão 500).
- **Grants**: extrair privilégios, só dos objetos selecionados.

### Script gerado

- Fases em ordem de dependência (ADR 0007): domínios, generators/sequences, exceptions, UDFs, collations, tabelas sem FKs, views em ordem topológica, Cabeçalhos vazios de rotinas/functions/packages, índices e FKs, corpos, triggers, `SET GENERATOR`, descrições, grants.
- Para `isql` e DBeaver, com `SET TERM`; senha nunca no arquivo.
- Charset do script conforme ADR 0009; `CREATE DATABASE` sempre com `DEFAULT CHARACTER SET`; aviso se houver colunas `NONE`.
- Dados: `INSERT`s em ordem de FK, lidos em streaming.

## Fora do escopo

- Aba **IBEBlock**.
- `REINSERT`, "Exclude IBE$/TMP$", `UPDATE … DESCRIBE`, `COMMENT` opcional (a saída é sempre `COMMENT ON`).
- Formatos CSV/JSON (ficam com o Data Transfer nativo do DBeaver).
- Servidores que não sejam Firebird 5 (pode funcionar com outras versões, sem garantia).
- Valores de Colunas array no Script de dados (decisão adiada, ver ADR 0010).

## Testes

- **Referência do IBExpert** (ADR 0008): saída gerada à mão a partir do banco sintético em `samples/`, versionada em `golden/`; comparação por statement normalizado e por objeto; Diferenças aceitas em `allowed-diffs`.
- **Ida e volta** para tudo o que o IBExpert não gera (Packages, Functions, Sequences, identity, Publications, Mappings, tipos novos) e para provar que o script roda de ponta a ponta.
- Rodam na suíte normal, contra o Firebird 5 local, sem o IBExpert instalado.

## Critérios de aceite

1. Num banco real, o Script de metadata gerado roda de ponta a ponta no `isql` contra um banco vazio, sem erro.
2. Para o subconjunto comum, a comparação com a Referência do IBExpert passa, e toda divergência está em `allowed-diffs`.
3. Ida e volta verde para todos os tipos de objeto do Firebird 5 listados.
4. O Script de dados recria as tabelas com o mesmo conteúdo, respeitando FKs.
5. Extração cancelável, em background, sem travar a UI, num banco do porte de referência.
6. Banco `WIN1252` com colunas `NONE` exporta e recria sem perda de acentos (ADR 0009).
7. Nenhuma senha aparece no arquivo gerado.
8. A feature instala e desinstala sozinha, sem tirar o depurador.

## Dependências externas

- **Humano**: gerar as Referências do IBExpert (num servidor Firebird 2.5, se o IBExpert não abrir o banco 5) toda vez que o banco sintético mudar.
