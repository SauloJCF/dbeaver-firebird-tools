# 0004 — UI de debug padrão do Eclipse, com `org.eclipse.debug.ui` empacotado

Status: aceito — 2026-10-13 · revisado 2026-10-14 (forma de instalação: `dropins` → repositório p2) · revisado 2026-10-19 (DBeaver 26.2.2, `debug.ui` 3.22.0)

> Nota: renomeado na ADR 0006. O plugin passou de `dev.fbdebug.dbeaver` para `dev.fbtools.dbeaver.psqldebug` e o zip, de `firebird-psql-debugger-<versão>.zip` para `firebird-tools-<versão>.zip`. A decisão abaixo não muda.

## Contexto

O DBeaver CE 26.2.2 traz `org.eclipse.debug.core`, mas não `org.eclipse.debug.ui` nem o antigo framework `org.jkiss.dbeaver.debug.*`. As dependências do `debug.ui` (`ui.console`, `ui.ide`, `ui.editors`, `jface.text`, `core.variables`) estão presentes.

O protótipo da issue 01 provou que o `org.eclipse.debug.ui` 3.20.0 (Eclipse 4.39, mesma release do `debug.core` 3.23.200 do DBeaver 26.0.2, a versão usada então) resolve e funciona dentro do DBeaver. Também mostrou que o DBeaver **não processa o `dropins/`**: o `org.eclipse.equinox.p2.reconciler.dropins` vem desligado no `bundles.info`.

O DBeaver fixado passou a ser o 26.2.2 (plataforma Eclipse 4.41, `debug.core` 3.24.0.v20260714-0955). O `debug.ui` empacotado segue a mesma regra e passou a ser o 3.22.0.v20260817-0925, do repositório `R-4.41-202608281142`.

## Decisão

Implementar o modelo de debug do Eclipse (debug target, threads, stack frames, variables, breakpoints) e distribuir o `org.eclipse.debug.ui` da mesma versão da plataforma do DBeaver junto do plugin.

- A sessão troca automaticamente para a perspectiva Debug.
- Atalhos padrão do Eclipse (F5 step into, F6 step over, F7 step return, F8 resume, Ctrl+Shift+B breakpoint).
- Breakpoints não persistentes: existem só enquanto o editor da Rotina está aberto.
- **Instalação via p2.** O build gera um repositório p2 em zip (`firebird-psql-debugger-<versão>.zip`) com uma feature que inclui o plugin e o `org.eclipse.debug.ui` fixado. Para instalar, cada pessoa usa "Help > Install New Software > Add > Archive". Para desinstalar, usa "Help > Installation Details". É o mesmo caminho do debugger oficial do DBeaver ("DBeaver Debug extension"), que também depende do `debug.ui` vindo de fora.

## Alternativas descartadas

- **Views próprias**: reimplementariam o que o `debug.ui` já entrega (Variables, Breakpoints, Expressions, ruler de breakpoint, edição de variáveis).
- **Atalhos do IBExpert/Delphi**: preteridos em favor do padrão Eclipse.
- **Jars no `dropins/`** (decisão original): o DBeaver ignora a pasta, porque o reconciler não é iniciado. Só funciona iniciando o reconciler à mão (console OSGi) ou mexendo no `dbeaver.ini`/`config.ini`. Além disso, tirar os jars não desinstala, e o `bundles.info` fica apontando para eles até o reconciler rodar de novo.
- **Editar o `bundles.info` à mão**: o próprio p2 reescreve o arquivo.

## Consequências

- O `debug.ui` precisa ser revalidado a cada upgrade do DBeaver (build fixado na 26.2.2).
- Distribuição em um único arquivo, o zip do repositório p2, com o plugin (runtime ANTLR embutido), o `org.eclipse.debug.ui` e a feature.
- O DBeaver não tem bundle pool p2 na pasta de instalação (falta o `artifacts.xml` na raiz). Por isso o p2 guarda os bundles instalados em `configuration/org.eclipse.osgi/<id>/data/<hash>/`, uma área de dados do `p2.extensionlocation`. Vale para qualquer extensão instalada por p2 no DBeaver, e não só para esta. Efeito: **iniciar o DBeaver com `-clean` remove o plugin em silêncio**, e é preciso reinstalar.
- O plugin não é assinado: na instalação, o p2 pede para confiar no conteúdo não assinado.
- O DBeaver converte `http://` em `https://` nos repositórios p2. Se um dia o repositório for publicado num servidor interno, esse servidor precisa de HTTPS. Para a distribuição por arquivo isso não importa.
- Conflito de atalho: F7 (step return) colide com o F7 do resultset do DBeaver (`resultset.grid.togglePreview` no 26.2.2), cujo contexto o editor SQL deixa sempre ativo. Resolvido na issue 18 com um contexto de atalhos do plugin, filho do de debug do Eclipse e ativo só durante a Sessão de debug.
