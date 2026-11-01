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

- [ ] Comparação com a Referência do IBExpert passa para procedures (subconjunto 2.5).
- [ ] Ida e volta verde para procedures, functions, packages e o caso cíclico.
- [ ] Fecha o critério 3 da 04: a view sobre procedure selecionável (e a que lê essa view) sai depois do Cabeçalho vazio e o script gerado roda num banco vazio, sem aviso e sem criar a procedure à mão.
- [ ] O corpo extraído é exatamente a Fonte do banco, sem normalização.
- [ ] Script com `SET TERM` roda no `isql`; sem `SET TERM`, o texto não contém `;` ambíguos.

## Dependência humana

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments
