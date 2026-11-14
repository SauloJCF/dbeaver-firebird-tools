## Agent skills

### Issue tracker

Issues e specs ficam como arquivos markdown em `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Os cinco papéis padrão (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), registrados como linha `Status:` em cada issue. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` + `docs/adr/` na raiz. See `docs/agents/domain.md`.

## Build e testes

O build é Maven/Tycho (`.\mvnw`, na raiz; JDK 21). Detalhes para humanos no README; o que importa a quem roda:

- Rode **sempre na raiz e por inteiro**. `-pl`/`-rf` num módulo só falham com `Could not resolve target platform specification artifact`: o `.target` é um módulo do reactor.
- `.\mvnw verify` no dia a dia. **`.\mvnw clean verify`** ao mudar a gramática, renomear ou mover classes, ou quando aparecer `X cannot be resolved` com a classe existindo (a compilação incremental do Tycho recompila só parte dos testes). Na dúvida, `clean`.
- Leva uns 2 minutos, quase tudo em testes contra o Firebird 5 local (`fbdebug-test.properties`: depurador e exportação; o banco é apagado e recriado). Sem ele, `-DskipTests` e diga que os testes **não rodaram**.
- Vale quando termina em `BUILD SUCCESS`. Ao relatar, cite `Tests run` por módulo se você o viu; não afirme o que não leu.
- **Sandbox de agente:** o Tycho falha com `Unable to establish loopback connection` (`Java11HttpTransportFactory`), mesmo com `-o`, IPv4 forçado ou sandbox desligado. Não é erro do código. Rode no terminal do usuário (`mcp__terminal__run_in_terminal`, depois `read_terminal` com `wait_for_output_ms` ≤ 295000; a saída chega em blocos, então leia em loop até `BUILD SUCCESS`/`BUILD FAILURE`). Um subagent sem essa ferramenta deve relatar o bloqueio, não tentar contornar.
