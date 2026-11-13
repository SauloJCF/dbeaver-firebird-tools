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

- [x] Comparação com a Referência do IBExpert passa para o subconjunto comum (triggers de tabela, exceptions, roles; generators). **UDFs ficaram de fora por decisão do usuário (2026-11-12):** a Referência não tem nenhuma, e elas só têm ida e volta.
- [x] Ida e volta verde para os tipos exclusivos do Firebird 5 (sequence com `INCREMENT`, triggers de banco e DDL, publications, mappings, collations).
- [x] Triggers saem depois dos corpos das rotinas que chamam (Fase correta).

## Dependência humana

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments

### Nota da issue 05 (agente, 2026-11-01)

- Exceptions precisam sair **antes** dos corpos das rotinas (o corpo de `SP_FECHAR_PEDIDO` usa `E_PEDIDO_FECHADO`). Ao implementá-las, remover `ExportTestDatabase.createSyntheticExceptions()` e as chamadas em `DomainsAndGeneratorsExtractionTest`, `TablesExtractionTest`, `ViewsExtractionTest` e `RoutinesExtractionTest`.
- Triggers que leem views ou chamam rotinas devem sair depois do bloco `Routine bodies` / `Views that read routines`.
- Desvio herdado da 02: `SET GENERATOR` sai junto de cada `CREATE GENERATOR`, mas a ADR 0007 e a spec o põem numa Fase própria, depois dos triggers. Mover aqui ou registrar a decisão de manter.

### Implementação (agente, 2026-11-03)

- **Novo:** `TriggerCatalog` (triggers de tabela, banco e DDL; fora os de sistema e os das constraints `CHECK`) e `ObjectCatalog` (exceptions, roles, collations, BLOB filters, UDFs, mappings locais e a publication padrão). `Selection` ganhou `exceptions`, `udfs`, `collations`, `filters`, `roles`, `triggers`, `publications` e `mappings` (construtores antigos mantidos, com os tipos novos vazios); `ExtractionOptions` ganhou `useSequence`. Aba **Objects** com os 16 grupos e o checkbox "SEQUENCE no lugar de GENERATOR"; `ExtractMetadataHandler` passa um mapa grupo → nomes ao wizard.
- **Tipo do trigger** decodificado de `RDB$TRIGGER_TYPE` (máscara de bits; valores medidos num Firebird 5, não tirados da documentação). DDL: bit 14 marca o tipo, bit 0 é a fase (1 = `AFTER`, o contrário dos triggers de tabela), bit `n` liga o evento `n`; "ANY DDL STATEMENT" é todos os bits ligados. Teste sem banco: `TriggerEventTest`.
- **Fonte do trigger** sai exatamente como está no banco (já começa em `AS`). Trigger com `SQL SECURITY` e UDR (`EXTERNAL NAME … ENGINE`) previstos no gerador; só `SQL SECURITY` tem teste (no banco sintético ampliado). **UDR sem teste.**
- **Ordem das Fases agora:** collations, BLOB filters, UDFs, domínios, generators/sequences, exceptions, roles, mappings, tabelas…, corpos e views que leem rotina (ADR 0011), **triggers**, `SET GENERATOR`, publication, descrições. Desvios da spec/ADR 0007, de propósito: (a) collations, filters e UDFs vão **antes dos domínios**, porque um domínio pode usar `COLLATE`, e a UDF antes das colunas calculadas; (b) mappings logo depois das roles, que eles apontam; (c) publication depois de tudo que ela referencia.
- **`SET GENERATOR` movido** para depois dos triggers (resolve o desvio herdado da 02: a spec e a ADR 0007 o põem lá). Os testes antigos continuaram verdes sem ajuste.
- **Sequences:** `CREATE SEQUENCE x START WITH n INCREMENT BY m` quando o generator tem início ≠ 1 ou incremento ≠ 1 (o padrão do Firebird 5 é 1 e 1; `CREATE GENERATOR` não aceita esses parâmetros), ou sempre com a opção. O valor atual vem de `GEN_ID(g, 0)` e sai no `SET GENERATOR`; num `START WITH 100` recém-criado o atual é 99 (início menos incremento), e a ida e volta preserva isso. Testado: `RDB$INITIAL_VALUE`, `RDB$GENERATOR_INCREMENT` e o próximo `NEXT VALUE FOR` iguais na cópia.
- **Collations:** os três atributos (`PAD SPACE`/`NO PAD`, `CASE`, `ACCENT`) saem sempre explícitos. Medido em todas as combinações com `UNICODE`, `UNICODE_CI` e `WIN_PTBR`: o atributo que a collation base já traz soma ao declarado, e só com os três explícitos o resultado é exatamente o da origem. A especificação de ICU sai sem `COLL-VERSION` e `ICU-VERSION`, que o servidor acrescenta sozinho.
- **Publications:** o Firebird 5 só tem a `RDB$DEFAULT` (não há `CREATE PUBLICATION`; é `ALTER DATABASE INCLUDE/EXCLUDE … PUBLICATION`). O grupo só aparece na aba Objects se ela estiver configurada. Saem `INCLUDE TABLE a, b TO PUBLICATION` (ou `INCLUDE ALL` + `EXCLUDE TABLE … FROM PUBLICATION` com inclusão automática) e `ENABLE PUBLICATION`, só das tabelas selecionadas.
- **Mappings:** só os **locais** (`RDB$AUTH_MAPPING`); os globais ficam no banco de segurança do servidor, não neste banco, e não saem. Modo de autenticação desconhecido gera aviso e o mapping é pulado.
- **Roles:** com `SET SYSTEM PRIVILEGES TO …` (bit n+1 = n-ésimo privilégio; mapa medido).
- **UDFs:** retorno por valor, `PARAMETER n`, `CSTRING`, `FREE_IT`, `BY DESCRIPTOR`, `BLOB` e charset. Ida e volta com 5 UDFs.
- **Banco sintético ampliado:** `samples/export-db/create-export-db-fb5.sql`, aplicado em cima do `create-export-db.sql` só nos testes (`ExportTestDatabase.syntheticDatabaseWithFirebird5Objects`). Fica **fora da Referência do IBExpert**, então a Referência atual continua valendo e não precisa ser regenerada para ele.
- `ExportTestDatabase.createSyntheticExceptions()` removido, com as chamadas nos quatro testes; `ObjectKey` (teste) passou a reconhecer `DECLARE EXTERNAL FUNCTION`, `DECLARE FILTER` e `CREATE MAPPING`.
- Testes novos: `OtherObjectsExtractionTest` (15, banco real) e `TriggerEventTest` (4, sem banco). Teste: `.\mvnw -o verify` na raiz, depois de apagar `target/classes` e `target/test-classes` da exportação (o Tycho compila incremental e perde as classes de teste): BUILD SUCCESS; 231 testes do depurador e 82 da exportação, 0 falhas. Firebird 5 local: sim.

**Pendências e decisões em aberto**

- **UDFs adiadas (decisão do usuário, 2026-11-12):** o código e a ida e volta ficam, mas a comparação com a Referência do IBExpert não os cobre. Se um dia valer, gerar a Referência com UDFs (Dependência humana).
- **Aba Objects no DBeaver não foi verificada à mão:** os 8 grupos novos e o checkbox compilam e resolvem contra o DBeaver 26.2.2, mas ninguém abriu o wizard (a 05 fez essa verificação com o agent-desktop).
- O script gerado foi executado via JDBC (`ExportTestDatabase.run`, que respeita `SET TERM`), não no `isql` (não há `isql` do Firebird 5 nesta máquina).
- **Descrições (`COMMENT ON`) dos objetos novos** (exceptions, triggers, roles, collations, filters, UDFs, mappings) ficam para a **07**, que já cuida de descrições e grants; hoje só domínios e generators têm.
- Sem aviso: trigger que referencia tabela fora da seleção; trigger ativo de DDL no destino dispara nas criações seguintes do script (os do sintético ficam inativos de propósito).
- Sem teste: trigger UDR e `SQL SECURITY` em package/trigger fora do caso sintético; mapping com `USING` fora de `P` e `M`.

### Ajustes da revisão (agente, 2026-11-12)

- Trigger com tipo que a extração não decodifica (evento de banco desconhecido, tipo sem ação) agora avisa e é pulado, em vez de estourar o `IndexOutOfBounds` e derrubar a extração e o wizard. Teste: `typesWithoutAKnownEventDecodeToNull`.
- `readMappings` e `readPublication` só tratam como "vazio" o erro de tabela/coluna desconhecida e de falta de permissão; qualquer outro (cancelamento, conexão) sobe. `listMappings`/`listPublications` passaram a lançar `SQLException`.
- UDF com `BY SCALAR_ARRAY` (mecanismo 4, medido; o 3 é BLOB) agora sai com o modificador; `UDF_MATRIZ` entrou no banco sintético ampliado (6 UDFs).
- `INCLUDE ALL TO PUBLICATION` só sai se houver tabela selecionada (teste novo), e a lista de incluídas só é calculada quando usada. Mapping ignorado por modo desconhecido agora conta no progresso.
- Imports no lugar dos nomes qualificados; Javadoc de `ExtractionOptions` quebrado direito.
- **Não alterado:** (a) mapping com `USING` fora de `P`/`M`: o achado dizia que o Firebird grava `S` e `*`, mas só medi `P` (com plugin ou sem, que é `ANY PLUGIN`) e `M`; `USING ANY PLUGIN SERIALLY` nem é aceito pelo 5.0. Segue aviso + pulo para qualquer outro valor. (b) `SET GENERATOR` lido durante a escrita e emitido depois: a extração não é um snapshot de qualquer forma, e a movida já está na spec e na ADR 0007. (c) Construtores de `ExtractionOptions`: ficam, os testes os usam; um builder é refatoração à parte. (d) `teste-extract.sql` é do commit `44ff999 "Save"`, não deste trabalho; não mexi.
- `.\mvnw -o verify`: BUILD SUCCESS; 231 testes do depurador e 84 da exportação, 0 falhas.

### Decisão (2026-11-12)

- UDFs deixadas de lado por enquanto: saem do critério 1, que passa a valer para triggers, exceptions, roles e generators (verificado). O código das UDFs e o teste de ida e volta continuam na branch.
