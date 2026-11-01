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

- [x] Comparação com a Referência do IBExpert passa para tabelas, índices e FKs, com as diferenças em `allowed-diffs`.
- [x] Ida e volta verde, incluindo identity, GTT, índices parciais/computados e os tipos novos (sem Referência do IBExpert).
- [x] Ordem das tabelas independe da ordem alfabética: FKs vão para a Fase seguinte, então ciclos entre tabelas não quebram o script.

## Dependência humana (cumprida em 2026-11-01)

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments

- 2026-10-31 (ADR 0010, proposta): a Coluna array (dimensões declaradas na coluna ou no domínio, `RDB$FIELD_DIMENSIONS`) tem de sair com as dimensões no Script de metadata (ex.: `INTEGER [1:5]`), ou no mínimo avisar. Nunca emitir como escalar: num banco real isso recria a coluna com tipo errado, em silêncio. A `C_VETOR` saiu do Banco sintético em 2026-10-31, então esse caminho está sem teste (comparação com a Referência do IBExpert e ida e volta) até a ADR 0010 ser aceita e a tabela de arrays voltar. A `C_VETOR` foi retirada à mão da Referência do IBExpert (sem regenerar no IBExpert, ver `golden/README.md`); regenerar quando possível (ADR 0010, Consequências). Não resolver com `allowed-diffs`. Nenhum critério marcado nesta atualização.

### Entrega (agente, 2026-11-01)

Feito, em `dev.fbtools.dbeaver.export` (`TableCatalog`, `ExtractionOptions`, `Rdb`, mudanças em `MetadataExtractor`, `FieldType`, `Selection` e na aba Objects):

- Fases: `CREATE TABLE` (sem FKs) + `PRIMARY KEY`/`UNIQUE`; depois, após todas as tabelas, colunas `COMPUTED BY` separadas (se a opção estiver ligada), índices, `CHECK`, FKs e `ALTER TABLE ... ALTER COLUMN ... RESTART WITH` das identities. Ciclos entre tabelas e a ordem alfabética não quebram o script (teste com `ZETA`/`ALFA`).
- Colunas: domínio ou tipo inline, `DEFAULT`, `NOT NULL`, `COLLATE`, `COMPUTED BY`, identity (`ALWAYS`/`BY DEFAULT`, `START WITH`/`INCREMENT BY`), `INT128`, `DECFLOAT`, `TIME ZONE`, `BOOLEAN`. GTT com `ON COMMIT PRESERVE/DELETE ROWS`; tabela externa com `EXTERNAL FILE`.
- Constraints: nomes `INTEG_n` saem sem nome (o servidor dá outro ao recriar); índice de constraint sai com `USING [DESC] INDEX` quando o autor escolheu o nome ou ele é descendente. Índices: simples, compostos, descendentes, parciais (`WHERE`), computados, inativos (`ALTER INDEX ... INACTIVE`).
- **Coluna array (ADR 0010, comentário acima):** colunas e domínios array saem com as dimensões (`INTEGER [1:5]`, `VARCHAR(5) [0:1,1:2] CHARACTER SET WIN1252`), nunca como escalar. Testado na ida e volta em banco próprio (`firebird5ColumnsIndexesAndTablesRoundTrip`), sem mexer no Banco sintético nem na Referência. A parte dos *dados* da ADR 0010 (omitir o array no Script de dados) continua proposta e fica para o issue 08.
- Opções (aba Objects): `CREATE DATABASE` (sem usuário e senha, com `PAGE_SIZE` e `DEFAULT CHARACTER SET`; a collation padrão do banco entra como `COLLATION`, ver as investigações abaixo), `CHARACTER SET`/`COLLATE` sempre, `COMPUTED BY` separado e Always quote identifiers (os nomes de collation continuam sem aspas). Aviso por `progress.warn` se houver domínio ou coluna `NONE` (só quando o banco não é `NONE`).
- Aba Objects lista Tables e Indexes (índices de constraints não aparecem; saem com a tabela).
- Correção de passagem: `MetadataExtractor.extract` ignorava o parâmetro `setNames`, então a opção de codificação UTF-8 gravava `SET NAMES <charset do banco>` num arquivo UTF-8. Agora usa `setNames`.

Teste: `.\mvnw -o verify` na raiz: BUILD SUCCESS; 231 testes do depurador e 39 da exportação (7 novos em `TablesExtractionTest`: Referência, ida e volta do Banco sintético, ciclo de FKs, ida e volta do que é só do Firebird 5, opções, seleção parcial, aviso `NONE`), 0 falhas.

Pendências:

- **Critério 1 fica aberto (Dependência humana):** a comparação com a Referência do IBExpert passa hoje para tabelas, constraints e índices, sem nenhuma entrada nova em `allowed-diffs`. Mas a Referência ainda é a de 2026-10-30 com a `C_VETOR` retirada à mão (ADR 0010); regenerar no IBExpert a partir de `create-export-db.sql` e rodar de novo antes de marcar.
- Não implementado (outros issues): `COMMENT ON` de tabelas, colunas e índices (issue 07); os triggers `CHECK` que o servidor cria ficam fora desta leitura e o issue 06 não deve exportá-los como triggers de usuário (`RDB$CHECK_CONSTRAINTS`); as colunas `COMPUTED BY` separadas hoje vêm antes das rotinas, e o issue 05 deve movê-las para depois dos cabeçalhos se a expressão chamar function. O mesmo vale para `DEFAULT`, `CHECK` e índice computado que chamem function PSQL: as Fases de tabela e de índice vêm antes das rotinas.
- Verificação manual no DBeaver da aba Objects (Tables/Indexes e as quatro opções) não foi feita; só compila e resolve contra o DBeaver 26.2.2.
- Observação sobre o ambiente: `fbdebug-test.properties` com `database=C:\...` (barras invertidas) é lido pelo `Properties` sem as barras, e os bancos `export-*.fdb` dos testes foram parar em `C:\Windows\System32` do servidor. Funciona, mas convém usar `/` no caminho.

### Ajustes da revisão (agente, 2026-11-01)

Aplicados depois do `code-reviewer`; `.\mvnw -o verify` na exportação: 40 testes, 0 falhas.

- Seleção parcial: a seleção continua literal (dá para extrair só um índice ou uma tabela para um banco que já tem o resto), mas FK para tabela e coluna com domínio fora da seleção agora geram um aviso (`warnsWhenSelectedTablesDependOnObjectsOutsideTheSelection`).
- `CHECK` sem trigger no catálogo é pulada com aviso, em vez de sair `CHECK ()`.
- Identity com `RDB$INITIAL_VALUE`/`RDB$GENERATOR_INCREMENT` nulos (banco migrado) vale 1.
- `listTables()` lê só os nomes.
- Não aplicado: PK/UNIQUE sobre coluna `COMPUTED BY` com a opção "separado" — o Firebird recusa indexar coluna calculada, então o caso não existe.
- Em aberto (precisam de verificação): o tipo declarado de uma coluna `COMPUTED BY` se perde (o catálogo não separa declarado de inferido; conferir o que o IBExpert faz na Referência) e a collation padrão do banco no `CREATE DATABASE` (ADR 0009) — confirmar no catálogo do Firebird 5 e registrar na ADR.

### Investigações dos dois pontos em aberto (agente, 2026-11-01)

- **Collation padrão do banco — corrigido.** A afirmação anterior ("o Firebird 5 não a guarda") estava errada. O `COLLATION` do `CREATE DATABASE` fica em `RDB$CHARACTER_SETS.RDB$DEFAULT_COLLATE_NAME` do charset padrão (testado no Firebird 5: `CREATE DATABASE ... DEFAULT CHARACTER SET WIN1252 COLLATION WIN_PTBR` muda esse campo; `RDB$DATABASE` não tem coluna de collation). `DatabaseInfo` lê o campo e o `CREATE DATABASE` sai com `COLLATION x` quando difere do nome do charset (ADR 0009 cumprida; teste `createDatabaseCarriesTheDefaultCollationOfTheDatabaseCharset`). 41 testes da exportação, 0 falhas.
- **Tipo de coluna `COMPUTED BY` — decisão pendente.** O catálogo grava o tipo declarado quando há (`C VARCHAR(10) COMPUTED BY (A || B)` fica `VARCHAR(10)`, `E NUMERIC(9,2) COMPUTED BY (1)` fica `NUMERIC(9,2)`) e o inferido quando não há (`COMPUTED BY (A || B)` fica `VARCHAR(40)`), sem marca que os distinga. Hoje o script omite o tipo, como o IBExpert: o servidor reinfere ao recriar, então uma coluna com tipo declarado diferente do inferido muda de tipo. Emitir o tipo sempre preservaria o declarado, mas faria toda tabela com coluna calculada diferir da Referência (`PRODUTO`, `ITEM_PEDIDO`), o que exigiria entradas em `allowed-diffs` para tabelas inteiras. Fica a escolha do usuário: manter (igual ao IBExpert, risco só quando o tipo declarado difere do inferido) ou emitir o tipo (mais fiel, com diferença aceita registrada).

### Decisão: tipo de `COMPUTED BY` (usuário, 2026-11-01)

Mantido igual ao IBExpert: o script omite o tipo da coluna `COMPUTED BY` e o servidor reinfere ao recriar. Limitação conhecida: uma coluna cujo tipo declarado difere do inferido muda de tipo no banco recriado. Nenhuma mudança de código; sem entrada em `allowed-diffs`.

### Nova Referência do IBExpert (usuário e agente, 2026-11-01)

O usuário regenerou a Referência no IBExpert 2012.02.21 a partir do `create-export-db.sql` atual (sem `C_VETOR`), com as descrições como `COMMENT ON`. O arquivo substituiu `samples/export-db/golden/export-db.ibexpert.sql` (convertido de Windows-1252 para UTF-8). O metadata é idêntico à edição manual anterior; só mudaram o cabeçalho e os `DESCRIBE`, agora `COMMENT ON`. Com isso o `allowed-diffs` ficou vazio (as duas entradas de `DESCRIPTION` saíram) e o `README.md` da Referência foi atualizado.

`.\mvnw -o verify` na exportação: 41 testes, 0 falhas. A comparação com a Referência passa para tabelas, constraints e índices sem nenhuma diferença aceita; critério 1 marcado. A pendência da ADR 0010 sobre regenerar a Referência (Consequências) está cumprida; a ADR continua proposta, e a edição fica para o `architect`.
