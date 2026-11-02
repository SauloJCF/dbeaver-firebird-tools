# 05 — Procedures, functions e packages (cabeçalhos e corpos)

Status: ready-for-agent
Blocked by: 03

## O que construir

- Fase de **Cabeçalhos vazios** (procedures, functions de schema, headers de package) antes de índices/FKs e views que dependam delas; fase de **corpos** depois, via `ALTER` ou `CREATE OR ALTER` (opção).
- Parâmetros de entrada e de saída, defaults, `NOT NULL`, `TYPE OF`, `SQL SECURITY`, `DETERMINISTIC`, `EXTERNAL NAME` para UDR.
- Packages: header, body, rotinas privadas.
- `SET TERM` por padrão, opção "não usar `SET TERM`".
- Aba **Objects** passa a listar Procedures, Functions e Packages.
- Ciclos: banco sintético com A chama B e B chama A, e com uma procedure usada por uma view.
- **Herdado da issue 04:** mover o bloco `Views that read procedures` (hoje logo depois de `Views`, em `MetadataExtractor.writeViews`) para depois dos Cabeçalhos vazios e remover o aviso de procedure de `warnAboutMissingDependencies`. Avaliar também a view (e a coluna `COMPUTED BY`, `DEFAULT`, `CHECK` e índice computado) que chama function PSQL, que a leitura de `RDB$DEPENDENCIES` da 04 não cobre (só tipos 0, 1 e 5).

## Critérios de aceite

- [x] Comparação com a Referência do IBExpert passa para procedures (subconjunto 2.5).
- [x] Ida e volta verde para procedures, functions, packages e o caso cíclico.
- [x] Fecha o critério 3 da 04: a view sobre procedure selecionável (e a que lê essa view) sai depois do Cabeçalho vazio e o script gerado roda num banco vazio, sem aviso e sem criar a procedure à mão.
- [x] O corpo extraído é exatamente a Fonte do banco, sem normalização.
- [x] Com `SET TERM`, o script roda no `isql` (verificado).
- [x] Sem `SET TERM`, cada rotina termina em `END;`, e a opção avisa que nem o `isql` nem o DBeaver executam esse formato (verificado no DBeaver 26.2.2 em 2026-11-02: ver Comments).
- [x] Opção para omitir o cabeçalho `SET SQL DIALECT` / `SET NAMES`, que o DBeaver recusa.

## Dependência humana

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments

### Implementação (agente, 2026-11-01)

- `RoutineCatalog` lê procedures, functions (não legadas, fora de package) e packages de `RDB$PROCEDURES`/`RDB$FUNCTIONS`/`RDB$PACKAGES` e dos parâmetros (domínio, `TYPE OF`, `TYPE OF COLUMN`, default, `NOT NULL`, `SQL SECURITY`, `DETERMINISTIC`, `EXTERNAL NAME … ENGINE` para UDR). Rotinas de package não saem à parte: o package leva o header e o body inteiros. UDFs legadas ficam para a 06.
- Fases novas em `MetadataExtractor`: **Cabeçalhos vazios** (`Routine headers`: packages, functions, procedures; corpo `EXIT` ou `SUSPEND` como o IBExpert, `RETURN NULL` nas functions) logo depois das views que não leem rotina; **corpos** (`Routine bodies`, `ALTER`, ou `CREATE OR ALTER` com a opção) depois de índices e FKs. `SET TERM ^ ;` por bloco; opção `noSetTerm` (checkbox na aba Objects) termina cada rotina em `END;`.
- Aba **Objects** lista Procedures, Functions e Packages (`listProcedures/listFunctions/listPackages`); `Selection` ganhou `procedures`, `functions`, `packages` (construtores antigos mantidos).
- **Mudança de desenho em relação ao que a 04 anotou:** o bloco `Views that read procedures` não fica logo depois dos Cabeçalhos. O Firebird 5 recusa `ALTER PROCEDURE` (`cannot delete DOMAIN RDB$n`) quando já existe uma view *sobre outra view* que lê a procedure (a coluna da view herda o domínio do parâmetro), então a view precisa do corpo definitivo da procedure, e o corpo que lê uma dessas views precisa dela pronta. Por isso, depois de índices e FKs, os corpos e as views (`Views that read routines`) saem intercalados em ordem de dependência (`writeBodiesAndViewsOverRoutines`). O aviso de procedure em `warnAboutMissingDependencies` foi removido; agora só avisa se a rotina que a view lê não está na seleção.
- **View que chama function/package** (item herdado da 04): o Firebird grava essa dependência como tipo 3 do campo anônimo da coluna da view, não como tipo 1; `ViewCatalog` passou a ler também isso e as trata como as que leem procedure. Testado com função e com função de package.
- **Coluna `COMPUTED BY`, índice de expressão e `CHECK` que chamam rotina:** a tabela é criada antes dos Cabeçalhos, então não há ordem que resolva. A extração **avisa** (`warnAboutRoutinesUsedByTables`) e o script só roda onde a rotina já existe. `DEFAULT` de coluna que chama function não foi avaliado.
- Testes (`RoutinesExtractionTest`, 11): Referência (PROCEDURE), ida e volta do sintético, parâmetros/`TYPE OF`/defaults/security, corpo idêntico à Fonte do banco (espaços, comentário e caixa do autor), ciclo A↔B com cadeia de views sobre A, view sobre function e sobre package, packages com rotina privada, `CREATE OR ALTER`, sem `SET TERM`, aviso de objeto de tabela, seleção parcial. O teste de view sobre procedure da 04 (`ViewsExtractionTest`) agora roda num banco vazio, sem criar a procedure à mão.
- Também rodei à mão: script do banco sintético extraído e executado no `isql` num banco novo, sem erro.
- Teste: `.\mvnw -o verify` na raiz (após apagar `target/classes` e `target/test-classes` da exportação): BUILD SUCCESS; 231 testes do depurador e 59 da exportação, 0 falhas.

**Decisões e pendências**

- O corpo do sintético usa as exceptions (`SP_FECHAR_PEDIDO`), que só saem na **issue 06**. Até lá, as ida e volta do sintético criam as três no destino com `ExportTestDatabase.createSyntheticExceptions()` (remover o helper quando a 06 chegar).
- **Critério do `SET TERM` ainda aberto.** A metade com `SET TERM` está verificada (teste `scriptRunsInIsqlOnAnEmptyDatabase` e a execução manual acima). Sem `SET TERM`, o corpo da rotina tem `;` por natureza; o texto sai com `END;` fechando cada rotina e sem nenhum `SET TERM`/`^`, e o teste prova que o mesmo conteúdo roda quando cada bloco é envolvido em `SET TERM`. Isso serve a um cliente que entende `BEGIN … END` (DBeaver), mas **não foi verificado no DBeaver** e o `isql` não executa esse formato. Decida se basta ou se o critério deve mudar.
- Verificação manual da aba Objects no DBeaver (grupos novos e o checkbox) não foi feita; só compila e resolve contra o DBeaver 26.2.2.
- **Dependência humana:** o Banco sintético não mudou, então a Referência atual já cobre as procedures. Functions e packages não existem nela (só Firebird 5), logo ficam só com a ida e volta.

### Ajustes da revisão (agente, 2026-11-02)

- Terminador (`^` ou `;`) vai para a linha seguinte quando a última linha da Fonte tem comentário `--` (`terminatorSeparator`); Fonte nula vira corpo vazio em vez de NPE; Javadocs, rótulo do checkbox (avisa que o `isql` não roda o formato sem `SET TERM`) e nome de teste corrigidos.
- Novos testes, todos verdes: Fonte de uma linha e com comentário final (a Fonte no destino é igual à da origem, então o `\n` depois do `AS` não altera o que o banco guarda) e package body que lê view sobre procedure (sai depois da view). `.\mvnw -o verify`: BUILD SUCCESS, 231 + 61 testes.
- **Abertos, dependem de decisão:** (B1) a ordem das Fases mudou em relação à ADR 0007 e à spec; falta ADR ou adendo e atualizar o verbete **Fase** do glossário (agent `architect`). (B2) reformular o critério do `SET TERM` e verificar o formato `END;` no DBeaver. Sem aviso hoje: ciclo corpo↔view, `TYPE OF COLUMN` de view tardia no Cabeçalho, UDR e `SQL SECURITY` de package sem teste, `DEFAULT` que chama function.

### Seguimento (agente, 2026-11-02)

- ADR 0011 criada (status `proposto`, aguarda aceite do usuário), nota na 0007, verbete **Fase** do glossário e spec atualizados (B1 resolvido na documentação).
- Ciclo corpo↔view agora gera aviso (`visitStep`), com teste; Javadoc do `ViewCatalog` alinhado. `.\mvnw -o verify`: BUILD SUCCESS, 231 + 62 testes.
- Segue aberto: B2 (critério do `SET TERM` e verificação do `END;` no DBeaver).
- B2: critério do `SET TERM` reescrito com o usuário (2026-11-02): `isql` com `SET TERM` já verificado; a metade sem `SET TERM` depende de rodar o script no DBeaver (passo humano) e fica `- [ ]` até lá. ADR 0011 aceita.

### Teste manual no DBeaver 26.2.2 (agente, com agent-desktop, 2026-11-02)

- Script gerado do EXPORT.FDB pelo wizard (aba Objects mostra Procedures, Functions e Packages e o checkbox) com "Não usar SET TERM", executado com Alt+X no VAZIO.FDB (vazio, só com a exception `E_PEDIDO_FECHADO` criada à mão).
- Tabelas, views, índices, FKs e os cabeçalhos vazios das procedures foram criados sem erro.
- **O DBeaver não executa o cabeçalho:** `SET SQL DIALECT 3;` e `SET NAMES UTF8;` dão `Token unknown` (são comandos do `isql`).
- **O DBeaver não executa o formato `END;`:** corta a rotina no primeiro `;` interno. O `ALTER PROCEDURE SP_FECHAR_PEDIDO` quebrou no `;` do `DECLARE VARIABLE` ("Unexpected end of command - line 6, column 38"), e o `BEGIN … END` seguinte virou instrução solta com `:ID_PEDIDO`, `:V_STATUS`, `:V_TOTAL` pedidos como parâmetros de bind. Cancelado na janela de bind; as outras duas procedures também têm `;` interno e não foram observadas. As três ficaram com o corpo vazio no banco.
- **Decisão do usuário (2026-11-02):** o formato sem `SET TERM` não roda em nenhum cliente testado (`isql` e DBeaver); a opção continua, com o rótulo e o Javadoc corrigidos, e o cabeçalho passa a ser opcional (`ExtractionOptions.omitHeader`, checkbox "Sem cabeçalho SET SQL DIALECT / SET NAMES"). Teste novo `omitHeaderDropsSetSqlDialectAndSetNamesOnly`.
- Para rodar no DBeaver hoje: usar o script com `SET TERM` e sem o cabeçalho. Não verificado: se o DBeaver executa `SET TERM ^ ;` (ele tem o comando `@delimiter`); fica para uma próxima verificação.
