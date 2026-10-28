# 10 — Fechamento: testes consolidados, desempenho e documentação

Status: ready-for-agent
Blocked by: 09

## O que construir

- Revisão do arquivo `allowed-diffs`: cada diferença aceita com uma frase explicando o porquê.
- Ida e volta sobre o banco sintético inteiro (todos os tipos de objeto e todos os dados) como teste único de ponta a ponta.
- Teste de desempenho e de memória com um banco do porte de referência (~380 tabelas, ~590 views, ~400 procedures): a extração termina, é cancelável e a UI não trava.
- Documentação: `docs/instalacao-export.md` (com prints), `docs/exportacao.md` (opções, Fases, charset e aviso de `-clean`, como no depurador), `docs/regenerar-referencias-ibexpert.md` (passo a passo para gerar as Referências), e README como índice.
- `spec.md` e glossário revisados com o que mudou durante a implementação.

## Critérios de aceite

- [ ] Critérios 1–8 da spec cumpridos num DBeaver 26.2.2 limpo, seguindo só a documentação. (passo humano)
- [ ] Suíte inteira verde, incluindo os 228 testes do depurador.
- [ ] Nenhuma `allowed-diffs` sem justificativa.

## Comments
