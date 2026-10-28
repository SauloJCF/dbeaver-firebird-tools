# 02 — Tracer bullet: domínios e generators até o arquivo

Status: ready-for-agent
Blocked by: 01

## O que construir

A fatia mais fina que atravessa todas as camadas.

- Bundle `dev.fbtools.dbeaver.export` + feature própria no zip p2 (instala e desinstala sozinha; ADR 0006).
- Menu **Firebird Tools > Extract Metadata…** e item no menu de contexto da conexão Firebird.
- Wizard mínimo com as abas **Objects** (só Domains e Generators, árvore e lista selecionada) e **Output** (arquivo, nome padrão `<conexão>_<AAAAMMDD-HHMM>.sql`, último diretório lembrado).
- Extração em job de background, com progresso e cancelamento.
- Leitura de `RDB$FIELDS` e `RDB$GENERATORS` direto (ADR 0007), gravação em arquivo com `SET NAMES` do charset padrão do banco (ADR 0009) e `SET GENERATOR`.
- Banco de teste sintético em `samples/export-db/` (script de criação com domínios e generators; as demais fatias o ampliam) e **primeira Referência do IBExpert** para ele em `golden/`.
- Infraestrutura de teste: divisor de script em statements (respeitando `SET TERM`), normalizador e comparador por objeto; arquivo `allowed-diffs` (pode começar vazio ou quase).

## Critérios de aceite

- [ ] Extrair domínios e generators do banco sintético gera um script que roda no `isql` num banco vazio sem erro.
- [ ] O teste de comparação com a Referência do IBExpert passa, e cada divergência está em `allowed-diffs`.
- [ ] Teste de ida e volta dos mesmos objetos verde.
- [ ] Cancelar durante a extração remove o arquivo parcial e não deixa job pendurado.
- [ ] Banco `WIN1252` com acentos nos nomes de domínio, nas descrições e nos defaults exporta e recria sem perda.
- [ ] A feature instala e desinstala sozinha, sem tirar o depurador (critério 8 da spec).

## Dependência humana

- Gerar, no IBExpert, a Referência do banco sintético (Extract Metadata, opções padrão do print) e entregar o `.sql`. Se ele não abrir o banco 5, usar um servidor 2.5 só para essa etapa (ADR 0008).

## Comments
