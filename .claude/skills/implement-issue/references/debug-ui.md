# Área: Debug UI do Depurador PSQL

A camada Eclipse/DBeaver: modelo de debug do Eclipse, launch, breakpoints, Variables, Expressions, menus e perspectiva. A execução é do Interpretador; esta área o consome.

## Onde

- `plugin.xml` (extensões: launch config type, perspectiva, menus, commands, ícones), `IDebugTarget`/`IThread`/`IStackFrame`/`IVariable`, diálogo de launch.
- Ler antes: `GLOSSARY.md` (Launch, Sessão de debug, Fonte do banco, Step into elegível, Executado no servidor), ADRs 0001, 0004 e 0006.
- `org.eclipse.debug.ui` vem empacotado na feature do depurador (ADR 0004); use o que está lá.
- Fontes do DBeaver e do `debug.ui`: tag do GitHub e source jar em `~/.m2`.

## Regras do domínio

- **O Launch é o único início de sessão** (sem attach). Diálogo com parâmetros, isolamento (`READ COMMITTED` padrão), "commit ao terminar" (desmarcado), bloqueio se o editor tem alterações não salvas, troca para a perspectiva Debug.
- **Depura-se a Fonte do banco**; a única exceção é `EXECUTE BLOCK`, que vem do editor SQL.
- **Breakpoints vivem só enquanto o editor da Rotina está aberto** (não persistentes).
- **A Sessão de debug tem conexão e transação próprias**; ao terminar, rollback, salvo "commit ao terminar".

## Testes

Lógica testável fora do Eclipse vai em teste unitário; o que só se vê no DBeaver vira critério "passo humano" com objetos em `samples/sample-db/create-sample-db.sql` (passo 7 da skill). Bug de UI sem issue: skill `mattpocock-skills:diagnosing-bugs`.

## Fronteiras

Gramática e Interpretador são da área Interpretador. Dependências e exports em `MANIFEST.MF`, poms, features e p2 são da área Build.
