# Área: Build e empacotamento

Estrutura multi-módulo e distribuição: poms, `MANIFEST.MF` (dependências e exports), `build.properties`, features, target (`.target`/`.launch`), repositório p2 e o zip instalável via "Help > Install New Software > Add > Archive".

## Onde

- Layout da ADR 0006: `bundles/`, `features/`, `releng/`; um bundle e uma feature por plugin, no mesmo zip p2, instaláveis e desinstaláveis separadamente. O zip sai em `releng/*.repository/target/`.
- `org.eclipse.debug.ui` fica **só** na feature do depurador (ADR 0004).
- Ler antes: ADRs 0004 e 0006, README (seções de build e zip).

## Regras do domínio

- **Renome é mecânico**: lógica de produção e testes só mudam no que o renome exige. Compare a contagem de testes antes e depois.
- Varra renomes com Grep e liste o que sobrou; o prefixo antigo `fbdebug` só pode ficar em `docs/adr/`, `.scratch/` e em `fbdebug-test.properties`.
- **A versão do DBeaver e do target só muda com pedido explícito**: cada upgrade é revalidado à mão.
- JDK 21 no `JAVA_HOME`. Mudança de renome ou estrutura: sempre `.\mvnw clean verify`.
- Renomear o repositório Git e instalar no DBeaver real são passos humanos: critério fica `- [ ]`.
