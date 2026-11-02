# 04 — Views em ordem topológica

Status: ready-for-agent
Blocked by: 03

## O que construir

- Fase de views, ordenada por `RDB$DEPENDENCIES` (view que lê view vem depois).
- Opção `CREATE OR ALTER` para views.
- Aba **Objects** passa a listar Views.
- Banco sintético com cadeia de views em vários níveis, incluindo uma view que depende de uma procedure selecionável (a view fica depois do Cabeçalho vazio da procedure; ver issue 05).

## Critérios de aceite

- [x] Comparação com a Referência do IBExpert passa para views.
- [x] Ida e volta verde, com ordenação correta numa cadeia de pelo menos 4 níveis.
- [x] Dependência cíclica entre views (impossível no Firebird) não é um caso; dependência de view para procedure selecionável é tratada. **Parcial: a verificação completa (view depois do Cabeçalho vazio, sem aviso e com ida e volta sem criar a procedure à mão) é feita na issue 05.**

## Comments

### Implementação (agente, 2026-11-01)

- `ViewCatalog` lê as views de `RDB$RELATIONS` (`RDB$VIEW_SOURCE`, colunas de `RDB$RELATION_FIELDS`) e as dependências de `RDB$DEPENDENCIES` (dependente tipo 1; lê tabela 0, view 1 ou procedure 5). Ordem topológica por busca em profundidade sobre os nomes em ordem alfabética, então a saída é estável.
- Fase de views entre as tabelas (com PK e UNIQUE) e a Fase de índices e FKs, como manda a ADR 0007. Opção `CREATE OR ALTER` (`ExtractionOptions.createOrAlter`, checkbox na aba Objects); a aba Objects lista Views.
- **View que lê procedure selecionável:** sai num bloco à parte, `Views that read procedures`, logo depois do bloco `Views`; isso vale também para a view que lê uma dessas views. As procedures ainda não saem no script (issue 05), então a extração avisa (`procedure X (view V)`) e o script só roda onde a procedure já existe. **Issue 05:** mover esse bloco para depois dos Cabeçalhos vazios (`writeViews` em `MetadataExtractor`) e tirar o aviso de procedure.
- A seleção continua literal: view que lê tabela ou view fora da seleção gera aviso, como as FKs.
- **Banco sintético não foi alterado.** A cadeia de 5 níveis e a view sobre procedure ficaram em bancos próprios dos testes (como a coluna array no issue 03): mexer em `create-export-db.sql` obrigaria regenerar a Referência do IBExpert (Dependência humana) e a comparação por objeto acusaria as views novas. Se quiser as duas no Banco sintético, é regenerar a Referência depois.
- Não implementado (outros issues): `COMMENT ON VIEW` e de colunas de view (issue 07); view que chame function PSQL (issue 05 deve avaliar, como já está anotado para colunas calculadas).

Teste: `.\mvnw -o verify` na raiz (após apagar `target/classes` e `target/test-classes` da exportação, por causa do Tycho incremental): BUILD SUCCESS; 231 testes do depurador e 47 da exportação (6 novos em `ViewsExtractionTest`: Referência, ordem das views do sintético, cadeia de 5 níveis com ida e volta, `CREATE OR ALTER`, view sobre procedure com bloco à parte e aviso, seleção parcial com aviso), 0 falhas.

Pendências: verificação manual da aba Objects no DBeaver (Views e o checkbox `CREATE OR ALTER`) não foi feita; só compila e resolve contra o DBeaver 26.2.2.

### Ajustes da revisão (agente, 2026-11-01)

- **Corrigido:** com "COMPUTED BY separado", a view que lia uma coluna calculada saía antes do `ALTER TABLE ... ADD`. O bloco `Computed columns` agora vem antes das views (`writeComputedColumns`); teste `viewReadingASeparatedComputedColumnComesAfterTheColumn`.
- **Critério 3 marcado como parcial (decisão do usuário):** a parte "view sobre procedure selecionável é tratada" só fica completa na issue 05 (view depois do Cabeçalho vazio, sem aviso). Hoje há o bloco à parte e o aviso, e a ida e volta desse caso só passa com a procedure criada à mão no destino. O trecho sobre ciclo entre views não exige nada.
- O critério 1 cobre só as 3 views do Banco sintético (cadeia e view sobre procedure não estão na Referência; ver o comentário anterior).
- Limitações conhecidas: com seleção parcial (só `V2`, que lê `V1`, que lê procedure), `V2` fica no bloco normal e o aviso cita `V1`, não a procedure; dependência de function PSQL/UDF não é lida (issue 05 deve avaliar).

### Fechamento do critério 3 (agente, 2026-11-01, issue 05)

A view sobre procedure agora sai depois do Cabeçalho vazio, sem aviso, e o script roda num banco vazio (teste `viewsThatReadSelectableProceduresComeAfterTheEmptyHeadersWithoutWarning`). O bloco ficou depois de índices e FKs, intercalado com os corpos, e não logo depois dos Cabeçalhos; ver Comments da 05.
