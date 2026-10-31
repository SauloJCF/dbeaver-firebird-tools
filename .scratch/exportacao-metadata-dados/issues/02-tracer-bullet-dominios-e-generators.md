# 02 — Tracer bullet: domínios e generators até o arquivo

Status: ready-for-agent
Blocked by: 01

## O que construir

A fatia mais fina que atravessa todas as camadas.

- Bundle `dev.fbtools.dbeaver.export` + feature própria no zip p2 (instala e desinstala sozinha; ADR 0006).
- Menu **Firebird Tools > Extract Metadata…** e item no menu de contexto da conexão Firebird.
- Wizard mínimo com as abas **Objects** (só Domains e Generators, árvore e lista selecionada) e **Output** (arquivo, nome padrão `<conexão>_<AAAAMMDD-HHMM>.sql`, último diretório lembrado).
- Extração em job de background, com progresso e cancelamento.
- Leitura de `RDB$FIELDS` e `RDB$GENERATORS` direto (ADR 0007), gravação em arquivo com `SET NAMES` do charset padrão do banco (ADR 0009) e `SET GENERATOR`.
- Banco de teste sintético em `samples/export-db/` (script de criação com domínios e generators; as demais fatias o ampliam) e **primeira Referência do IBExpert** para ele em `golden/`.
- Infraestrutura de teste: divisor de script em statements (respeitando `SET TERM`), normalizador e comparador por objeto; arquivo `allowed-diffs` (pode começar vazio ou quase).

## Critérios de aceite

- [ ] Extrair domínios e generators do banco sintético gera um script que roda no `isql` num banco vazio sem erro.
- [ ] O teste de comparação com a Referência do IBExpert passa, e cada divergência está em `allowed-diffs`.
- [ ] Teste de ida e volta dos mesmos objetos verde.
- [ ] Cancelar durante a extração remove o arquivo parcial e não deixa job pendurado.
- [ ] Banco `WIN1252` com acentos nos nomes de domínio, nas descrições e nos defaults exporta e recria sem perda.
- [ ] A feature instala e desinstala sozinha, sem tirar o depurador (critério 8 da spec).

## Dependência humana

- Gerar, no IBExpert, a Referência do banco sintético (Extract Metadata, opções padrão do print) e entregar o `.sql`. Se ele não abrir o banco 5, usar um servidor 2.5 só para essa etapa (ADR 0008).

## Comments

### Andamento (agente) — parcial, issue ainda aberto

Feito, sem depender do Firebird 5:

- Bundle `bundles/dev.fbtools.dbeaver.export` (esqueleto, só `package-info.java`), feature `features/dev.fbtools.dbeaver.export.feature` e entrada na `category.xml` ("Firebird Tools"); módulos no pom raiz. `.\mvnw verify -DskipTests` gera o zip com as duas features e os dois plugins.
- Banco sintético `samples/export-db/create-export-db.sql`: subconjunto comum ao Firebird 2.5 e ao 5 inteiro (domínios, generators, exceptions, roles, tabelas, índices, FKs, views, procedures, triggers, descrições, grants) mais dados. Validado no Firebird 5 e no IBExpert. Os objetos só do Firebird 5 (`create-export-db-fb5.sql`) ainda não existem.
- Referência do IBExpert 2012.02.21 em `samples/export-db/golden/export-db.ibexpert.sql` (convertida de Windows-1252 para UTF-8), com `README.md` de origem. O caminho `golden/` não estava definido nos documentos; ficou ao lado do banco sintético.
- Infraestrutura de teste em `bundles/dev.fbtools.dbeaver.export/test/.../testing/`: `ScriptSplitter` (respeita `SET TERM`, comentários e literais), `StatementNormalizer`, `ObjectKey`, `ScriptComparator` e `AllowedDiffs` (formato `TIPO NOME | motivo`, aponta entradas sem uso).
- Teste: `.\mvnw -pl releng/dev.fbtools.dbeaver.target,bundles/dev.fbtools.dbeaver.export verify` — 24 testes, 0 falhas, BUILD SUCCESS. Cobrem o divisor, o normalizador, o comparador e a leitura da Referência (contagem de objetos por tipo).

Pendente:

- Extração (leitura de `RDB$FIELDS` e `RDB$GENERATORS`), wizard, menu, job de background, `SET NAMES`, gravação em arquivo. Nada disso foi escrito.
- Arquivo `allowed-diffs` ainda não existe: só dá para preencher quando houver saída do plugin para comparar.
- Os testes que exigem o Firebird 5 (ida e volta, cancelamento, `WIN1252`) não podem rodar: na máquina só há o Firebird 2.5 (serviço padrão, porta 3050). O `.\mvnw verify` completo também não foi executado, pelo mesmo motivo.
- Diferenças já observadas entre a Referência e o que o plugin deve gerar (candidatas a `allowed-diffs`): `DESCRIBE` no lugar de `COMMENT ON`; `BLOB SUB_TYPE 1/0` no lugar de `TEXT/BINARY`; charset igual ao padrão e `COLLATE` omitidos; tabela `"LOG"` entre aspas; grants de tabelas de sistema (`RDB$FORMATS`, `RDB$PAGES`, `RDB$ROLES`); `GRANT <role> TO <usuário>` ausente.
