# 09 — Ordem de FK nos dados e "Add related objects"

Status: ready-for-agent
Blocked by: 07, 08

## O que construir

- Ordem de FK no Script de dados: tabelas pais antes das filhas (topológica sobre as FKs). Ciclos entre tabelas (FKs circulares): avisar e usar a ordem em que o ciclo se quebra com menos violações, ou, se não for possível, avisar que o script de dados precisa rodar com as FKs desativadas e dizer qual é a recomendação.
- Botão **Add related objects** na aba Objects: ao selecionar um objeto, traz as dependências (domínios, generators, exceptions, rotinas) a partir de `RDB$DEPENDENCIES`, transitivamente. Também no Data: tabelas referenciadas por FK das selecionadas.
- "Extract all" completo e seleção preservada ao voltar entre abas.

## Critérios de aceite

- [ ] Selecionar uma só tabela e usar "Add related objects" gera um script que roda num banco vazio sem erro de dependência.
- [ ] Banco sintético com FKs encadeadas e uma circular exporta os dados na ordem correta, ou avisa de forma explícita.
- [ ] Ida e volta de dados com FKs ativas passa.

## Comments
