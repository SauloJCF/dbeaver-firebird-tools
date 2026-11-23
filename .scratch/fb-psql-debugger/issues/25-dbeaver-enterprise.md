# 25 — Feature para o DBeaver Enterprise 26.2.0

Status: ready-for-human
Blocked by: —

Spec: `../spec.md`

## O que acontece

No DBeaver **Enterprise 26.2.0** (plataforma Eclipse 4.40), a feature atual instala sem erro, mas a view Debug quebra. A Rotina chega a rodar até o fim (o console mostra "Fim: rollback feito"). Porém, ao iniciar a Sessão de debug, aparece o diálogo:

> Cannot invoke "org.eclipse.ui.IWorkbenchPage.getPerspective()" because the return value of "org.eclipse.ui.IWorkbenchPartSite.getPage()" is null

Ambiente: EE 26.2.0.202609071204, Windows 11, Java 25.0.3, Firebird 5.0.3. Bundles do EE: `jface` 3.39.100, `debug.core` 3.23.300 e `debug.ui` 3.21.0.v20260525-0831 (o EE já traz o `debug.ui`).

## Causa

- A feature inclui o `debug.ui` 3.22.0 (Eclipse 4.41). Instalada no EE, ela troca o 3.21 do EE pelo 3.22.
- O 3.22 usa API que o EE não tem:
  - `NoSuchMethodError: ImageDescriptor.asDisabledDescriptor()`, que é do `jface` 3.40;
  - `NoClassDefFoundError: org/eclipse/debug/core/commands/IResumeOthersHandler`, que é do `debug.core` 3.24.
- Com isso, a `LaunchView` fica criada pela metade. Quando o `RoutineLauncher.showDebugPerspective()` troca de perspectiva, o `LaunchView.perspectiveActivated` dá o NPE do diálogo.
- Desinstalar a feature, ou dar "Revert" na Installation History, **não** devolve o 3.21: o p2 recalcula as dependências e fica com o 3.22 que já tem.

## Decisão

Revisão de 2026-11-23 da ADR 0004.

- Feature do CE: passa a exigir `org.eclipse.debug.core` >= 3.24.0. Em plataformas mais antigas, o p2 recusa a instalação.
- Feature nova `dev.fbtools.dbeaver.psqldebug.ee.feature`, com o mesmo plugin:
  - inclui o `debug.ui` 3.21.0.v20260525-0831, do repositório `R-4.40-202606010713`;
  - exige `debug.core` [3.23.0, 3.24.0).
- Incluir a versão exata, e não só exigir o `debug.ui`, faz o p2 trocar um 3.22 que tenha ficado no EE.
- A feature do EE sai num segundo zip, `firebird-tools-ee-<versão>.zip` (módulo `releng/dev.fbtools.dbeaver.repository.ee`). Num repositório só, o Tycho não resolve as duas features juntas, porque o `debug.ui` e o `debug.core` são singletons. O zip do EE leva só o Depurador PSQL: a Exportação não foi validada no EE.
- O código do plugin não muda.

## Critérios de aceite

- [x] `.\mvnw clean verify -DskipTests` gera os dois zips: o do CE com o `debug.ui` 3.22.0, e o do EE com o 3.21.0.
- [ ] No EE 26.2.0, a feature do EE instala, troca o `debug.ui` para o 3.21 e depura uma Rotina (breakpoint, steps, Variables) sem erro de `debug.ui` no `.log`.
- [x] No EE 26.2.0, o zip do CE dá erro de requisito do `debug.core` no *Install*, e nada é instalado.
- [ ] No CE 26.2.2, o zip do CE continua instalando e depurando como antes, e o zip do EE é recusado pelo requisito do `debug.core`.

## Comments

### 2026-11-23 — verificação

- O plugin `dev.fbtools.dbeaver.psqldebug` dos dois zips é o mesmo jar. O `debug.ui` 3.22.0 do zip do CE é idêntico ao da release `0.1.0.202610312006`. Na feature do CE, a única mudança é o `<requires>` do `debug.core`.
- Planos do p2 simulados com o `org.eclipse.equinox.p2.director -verifyOnly`, sobre cópias dos perfis de um CE 26.2.4 e de um EE 26.2.0:

  | Cenário | Resultado |
  |---|---|
  | CE + zip do CE | instala |
  | CE + zip do EE | recusado: falta `debug.core` [3.23.0,3.24.0) |
  | EE + zip do CE | recusado: falta `debug.core` 3.24.0 |
  | EE com o pacote de teste da feature do EE + zip do EE (remove o teste, instala a nova) | instala |
  | EE quebrado (feature do CE antiga + `debug.ui` 3.22) + zip do EE, removendo a feature do CE | instala |
  | EE quebrado + zip do EE, sem remover a feature do CE | conflito: o `dev.fbtools.dbeaver.psqldebug` é singleton. Por isso o guia manda desinstalar antes |

- A depuração no EE foi testada com um pacote montado à mão, com o mesmo jar do plugin e a mesma regra do `debug.ui` 3.21. Falta repetir com o zip deste build, e falta testar no CE.
