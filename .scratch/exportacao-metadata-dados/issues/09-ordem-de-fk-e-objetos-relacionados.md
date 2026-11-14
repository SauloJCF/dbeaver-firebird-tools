# 09 — Ordem de FK nos dados e "Add related objects"

Status: ready-for-agent
Blocked by: 07, 08

## O que construir

- Ordem de FK no Script de dados: tabelas pais antes das filhas (topológica sobre as FKs). Ciclos entre tabelas (FKs circulares): avisar e usar a ordem em que o ciclo se quebra com menos violações, ou, se não for possível, avisar que o script de dados precisa rodar com as FKs desativadas e dizer qual é a recomendação.
- Botão **Add related objects** na aba Objects: ao selecionar um objeto, traz as dependências (domínios, generators, exceptions, rotinas) a partir de `RDB$DEPENDENCIES`, transitivamente. Também no Data: tabelas referenciadas por FK das selecionadas.
- "Extract all" completo e seleção preservada ao voltar entre abas.

## Critérios de aceite

- [x] Selecionar uma só tabela e usar "Add related objects" gera um script que roda num banco vazio sem erro de dependência.
- [x] Banco sintético com FKs encadeadas e uma circular exporta os dados na ordem correta, ou avisa de forma explícita.
- [x] Ida e volta de dados com FKs ativas passa.

## Comments

- 2026-11-13 (implementação): `ForeignKeyOrder` (ordem topológica das tabelas por FK, estável pela ordem da lista; FK para a própria tabela é ignorada; o `DataExtractor` a aplica sempre) e `DependencyGraph` (Objetos relacionados: fecho transitivo sobre `RDB$DEPENDENCIES`, mais FKs e os domínios de colunas e parâmetros, que não ficam em `RDB$DEPENDENCIES`). Wizard: botão **Add related objects** na aba Objects (traz domínios, generators, exceptions, collations, UDFs, tabelas, views, rotinas, packages e triggers de que a seleção depende) e **Add related** na aba Data (tabelas referenciadas por FK, transitivamente). "Extract all" já cobria todos os grupos, e a seleção das abas fica no próprio wizard, então voltar entre abas a preserva (sem mudança de código).
- Testes: `ForeignKeyOrderAndRelatedObjectsTest` (10: ordem pura, ciclo, banco com FK circular, relacionados de tabela, view e procedure rodando num banco vazio, FKs transitivas). `.\mvnw clean verify`: BUILD SUCCESS, suíte inteira verde (depurador 231; export com os 10 novos). No ambiente do agente o Tycho não abre conexão loopback; rodou no Terminal do usuário.
- Decisões: (1) **Ciclo**: sai primeiro, entre as tabelas do próprio ciclo, a de menos pais pendentes; tabelas que só dependem do ciclo esperam. A extração avisa as tabelas do ciclo e recomenda rodar o script de dados com as FKs dessas tabelas desativadas (ou antes de criá-las); não gera `ALTER INDEX ... INACTIVE`. (2) **Triggers**: a pendência do issue 08 (os triggers disparam nos `INSERT`s) segue aberta; não ganhou `ALTER TRIGGER ... INACTIVE/ACTIVE` no script de dados. Fica para o 10 decidir. (3) Add related também traz tabelas e views de que rotinas e views dependem, não só as dependências "de tipo"; índices e triggers das tabelas trazidas não entram (um índice de usuário é escolhido à parte).
- Pendência humana: o wizard (botões) compila e foi coberto pela lógica testada, mas não foi aberto no DBeaver.
