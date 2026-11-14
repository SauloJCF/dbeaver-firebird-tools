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
- [x] Suíte inteira verde, incluindo os testes do depurador (231 hoje; o "228" do texto era a contagem antiga).
- [x] Nenhuma `allowed-diffs` sem justificativa.

## Comments

- 2026-11-13 (implementação): testes novos no bundle de exportação: `EndToEndRoundTripTest` (banco sintético inteiro com os objetos do Firebird 5: metadata + dados num banco vazio, re-extração idêntica, mesmo conteúdo das tabelas e mesmos valores de generators, sem senha no arquivo; e a conferência de que toda entrada do `allowed-diffs` tem motivo) e `ReferenceSizeExtractionTest` (banco com 250 domínios, 380 tabelas, 590 views, 400 procedures, 320 triggers e 260 generators: extrai em segundos sem reter memória depois; cancelamento, disparado no 50º objeto (fase dos domínios), para em milissegundos. O pico de memória do metadata e o cancelamento nas fases de views e rotinas não são medidos; o streaming dos dados é o do `DataExtractionTest`). `allowed-diffs` revisado: as 5 entradas já tinham motivo e todas são usadas.
- `.\mvnw clean verify` no terminal do usuário: BUILD SUCCESS. Depurador: 231 testes; exportação: 145 (3 do porte de referência, 2 da ida e volta de ponta a ponta). Zip gerado.
- Docs: `docs/instalacao-export.md`, `docs/exportacao.md` (opções, Fases, charset, aviso de `-clean`), `docs/regenerar-referencias-ibexpert.md`; README com a tabela de plugins apontando para elas. Spec: nota sobre os triggers no Script de dados. Glossário: nada mudou nas definições.
- Decisão (pendência do 08/09): o Script de dados **não** ganha `ALTER TRIGGER … INACTIVE/ACTIVE`; fica documentado em `exportacao.md` (rodar os dados antes de criar os triggers ou desativá-los).
- Pendências humanas: (1) critérios 1–8 da spec num DBeaver 26.2.2 limpo seguindo só a documentação (o primeiro critério do issue fica `- [ ]`); o wizard e o job nunca foram abertos no DBeaver, e "UI não trava" só se vê lá; (2) prints da instalação do export (a doc remete aos do depurador, não embute imagens); o critério 6 da spec (WIN1252 com `NONE`) é coberto pelo `DataExtractionTest`, não pelo teste de ponta a ponta, que é só UTF8 com opções padrão; a spec ainda lista a ordem das Fases com UDFs e collations depois das exceptions, diferente do código (a doc nova segue o código); (3) os `- [ ]` abertos dos issues 01 e 05 também dependem de instalar o zip e de rodar no DBeaver.
