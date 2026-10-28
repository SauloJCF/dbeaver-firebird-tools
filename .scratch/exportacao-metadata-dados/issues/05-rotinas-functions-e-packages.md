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

## Critérios de aceite

- [ ] Comparação com a Referência do IBExpert passa para procedures (subconjunto 2.5).
- [ ] Ida e volta verde para procedures, functions, packages e o caso cíclico.
- [ ] O corpo extraído é exatamente a Fonte do banco, sem normalização.
- [ ] Script com `SET TERM` roda no `isql`; sem `SET TERM`, o texto não contém `;` ambíguos.

## Dependência humana

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments
