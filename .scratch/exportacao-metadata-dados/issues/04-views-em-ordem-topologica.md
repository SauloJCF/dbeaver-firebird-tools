# 04 — Views em ordem topológica

Status: ready-for-agent
Blocked by: 03

## O que construir

- Fase de views, ordenada por `RDB$DEPENDENCIES` (view que lê view vem depois).
- Opção `CREATE OR ALTER` para views.
- Aba **Objects** passa a listar Views.
- Banco sintético com cadeia de views em vários níveis, incluindo uma view que depende de uma procedure selecionável (a view fica depois do Cabeçalho vazio da procedure; ver issue 05).

## Critérios de aceite

- [ ] Comparação com a Referência do IBExpert passa para views.
- [ ] Ida e volta verde, com ordenação correta numa cadeia de pelo menos 4 níveis.
- [ ] Dependência cíclica entre views (impossível no Firebird) não é um caso; dependência de view para procedure selecionável é tratada.

## Comments
