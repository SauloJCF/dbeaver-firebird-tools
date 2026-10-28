# 01 — Reorganização em múltiplos plugins e rename

Status: ready-for-agent
Blocked by: —

## O que construir

Implementar a ADR 0006, sem mudar nenhum comportamento do depurador.

- Renomear o prefixo `dev.fbdebug.dbeaver` para `dev.fbtools.dbeaver`, e o bundle do depurador para `dev.fbtools.dbeaver.psqldebug`: diretório em `bundles/`, pacotes Java (`dev.fbtools.dbeaver.psqldebug.*`), `MANIFEST.MF` (`Bundle-SymbolicName`), `plugin.xml` (ids de comandos, launch config type, perspectiva, extension points), `pom.xml`, `build.properties`, testes.
- Mesma renomeação para a feature (`features/dev.fbtools.dbeaver.psqldebug.feature`), o target (`releng/dev.fbtools.dbeaver.target`) e o repositório p2 (`releng/dev.fbtools.dbeaver.repository`), com o zip chamado `firebird-tools-<versão>.zip` e a `category.xml` com uma categoria "Firebird Tools".
- `groupId` e `artifactId` do pom raiz: `dev.fbtools.dbeaver:fbtools-parent`.
- O `org.eclipse.debug.ui` empacotado fica **só** na feature do depurador.
- Deixar o pom raiz, o target e o repositório prontos para receber um segundo bundle e uma segunda feature (sem criar o plugin da exportação ainda).
- Renomear o repositório Git/remoto para `dbeaver-firebird-tools` (passo humano) e ajustar README, `CLAUDE.md`, `docs/agents/*`, `NOTICE` e o `.launch` do target.
- README vira um índice dos plugins; a instalação detalhada do depurador vai para `docs/instalacao-psqldebug.md` (imagens continuam em `docs/images/`).
- Ajustar a referência a `fbdebug-test.properties` somente se o nome mudar; manter o arquivo atual funcionando.

## Critérios de aceite

- [ ] `.\mvnw clean verify` termina em `BUILD SUCCESS` com a mesma contagem de testes verdes de antes (228, contra o Firebird 5 local).
- [ ] O zip tem a feature `...psqldebug.feature`, o plugin renomeado e o `org.eclipse.debug.ui`.
- [ ] Nenhuma ocorrência de `dev.fbdebug` fora de `docs/adr/` (ADRs antigas, que são histórico) e deste diretório `.scratch`.
- [ ] Instalação limpa do novo zip num DBeaver 26.2.2 mostra o menu **PSQL Debug** como antes. (passo humano)
- [ ] ADR 0004 recebe uma nota ("renomeado na ADR 0006") sem reescrever a decisão.
- [ ] `GLOSSARY.md` e `CLAUDE.md` continuam coerentes (contexto único).

## Comments
