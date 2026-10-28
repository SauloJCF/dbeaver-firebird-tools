# 0006 — Vários plugins no mesmo repositório, com prefixo `dev.fbtools.dbeaver`

Status: aceito — 2026-10-28

## Contexto

O repositório nasceu com um único plugin (o depurador PSQL, `dev.fbdebug.dbeaver`). Vai ganhar um segundo, a exportação de metadata e dados (ADR 0007). Ambos são ferramentas de Firebird para o DBeaver CE 26.2.2, para o mesmo público. O repositório já é um monorepo Tycho (`bundles/`, `features/`, `releng/`), e nada foi publicado: ID e nome ainda podem mudar sem custo para ninguém.

## Decisão

- Um repositório só, renomeado para `dbeaver-firebird-tools`.
- Prefixo único `dev.fbtools.dbeaver`. Um bundle e uma feature por plugin: `dev.fbtools.dbeaver.psqldebug` (o depurador atual) e `dev.fbtools.dbeaver.export`.
- Um target platform único (DBeaver 26.2.2) e um único repositório p2, distribuído num zip só, com uma feature por plugin. Cada plugin se instala e desinstala sozinho.
- O `org.eclipse.debug.ui` empacotado (ADR 0004) pertence à feature do depurador, e não à do repositório inteiro.
- Bundle comum (`core`) só quando o segundo plugin precisar de algo do primeiro. Antes disso seria abstração especulativa.
- Contexto único: `GLOSSARY.md` com uma seção por plugin e ADRs em sequência.

## Alternativas descartadas

- **Repositório novo para a exportação**: duplicaria target, pin do DBeaver e infraestrutura de build, e deixaria o histórico (issues 01–23, ADRs) com o depurador.
- **Manter o ID `dev.fbdebug.dbeaver`**: só valeria para proteger instalações existentes, e não há nenhuma.
- **Bundle `core` desde já**: ninguém consome código compartilhado ainda.

## Consequências

- O rename (pacotes Java, `MANIFEST.MF`, `pom.xml`, `plugin.xml`, nome do zip, README) é uma issue própria, feita antes de qualquer código da exportação.
- O upgrade do DBeaver revalida todos os plugins de uma vez.
- Quando o segundo plugin precisar do `TestDatabase`/`DifferentialHarness`, eles saem para um bundle de apoio de testes.
