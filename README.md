# Firebird Tools para DBeaver

Plugins do DBeaver CE 26.2.2 para Firebird 5 (ADR 0006). Vocabulário em `GLOSSARY.md`, decisões em `docs/adr/`.

## Plugins

| Plugin | Bundle | O que faz | Documentação |
| ------ | ------ | --------- | ------------ |
| Depurador PSQL | `dev.fbtools.dbeaver.psqldebug` | Depura Rotinas PSQL (procedures, functions, triggers, packages) contra um banco local | [Instalação e uso](docs/instalacao-psqldebug.md), [Como funciona](docs/como-funciona.md) |
| Exportação de metadata e dados | `dev.fbtools.dbeaver.export` | Gera scripts de metadata e de dados do banco (ainda não implementado) | Spec em `.scratch/exportacao-metadata-dados/spec.md` |

Os plugins saem num zip só (repositório p2, `firebird-tools-<versão>.zip`), com uma feature por plugin. Cada um se instala e desinstala sozinho.

## Desenvolvimento

### Requisitos

- DBeaver CE 26.2.2 instalado na pasta padrão (`%LOCALAPPDATA%\DBeaver`). Os bundles dele são a target platform.
- JDK 21 no `JAVA_HOME` (o Tycho 5 exige Java 21).
- Acesso a `download.eclipse.org`: o `org.eclipse.debug.ui` vem do repositório do Eclipse 4.41.

### Build pela linha de comando

```
.\mvnw verify
```

Gera o plugin em `bundles/dev.fbtools.dbeaver.psqldebug/target/` e o repositório p2 instalável em `releng/dev.fbtools.dbeaver.repository/target/firebird-tools-<versão>.zip`.

O parser PSQL é gerado pelo ANTLR a partir de `bundles/dev.fbtools.dbeaver.psqldebug/antlr4/` em `target/generated-sources/antlr4/` (que o `build.properties` inclui como fonte). O runtime do ANTLR vai embutido no plugin (`lib/`).

Depois de mudar a gramática, use `.\mvnw clean verify`: a compilação incremental do Tycho não recompila as classes que só usam as constantes de token do léxico (o Java as embute), e um token novo desloca os números.

### Gerar o zip de instalação

O zip que a equipe instala no DBeaver é o repositório p2. Para gerá-lo:

1. Abra um terminal (PowerShell) na raiz do repositório.
2. Rode `.\mvnw clean verify`. Sem um Firebird 5 local configurado para os Testes diferenciais (ver abaixo), rode `.\mvnw clean verify -DskipTests`.
3. Confira `BUILD SUCCESS` no fim da saída.
4. O zip fica em `releng\dev.fbtools.dbeaver.repository\target\firebird-tools-0.1.0.<data-hora>.zip`. O sufixo é o *qualifier* do Tycho (`aaaaMMddHHmm`, em UTC), então cada build gera um nome novo.

O zip contém a feature, o plugin `dev.fbtools.dbeaver.psqldebug` (com o runtime do ANTLR embutido) e o `org.eclipse.debug.ui` (ADR 0004). O `clean` garante que não sobra zip de build anterior em `target/`.

### Testes diferenciais

O `verify` roda os testes JUnit do plugin, e os Testes diferenciais (ADR 0005) precisam de um **Firebird 5 local**:

1. Copie `fbdebug-test.properties.example` para `fbdebug-test.properties` na raiz (o arquivo fica fora do git) e ajuste host, porta, credenciais e o caminho do banco.
2. O banco indicado em `database` é **apagado e recriado** a cada execução da suíte. Use um arquivo só para isso.

Para gerar o build sem um Firebird disponível: `.\mvnw verify -DskipTests`.

### IDE: Eclipse IDE for RCP and RAP Developers

1. Instale o pacote "Eclipse IDE for RCP and RAP Developers", 2026-09 ou mais novo.
2. **File > Import > Maven > Existing Maven Projects**, apontando para a raiz do repositório e importando todos os módulos. Se o Eclipse oferecer o conector do Tycho ("Tycho Project Configurators"), aceite.
3. Abra `releng/dev.fbtools.dbeaver.target/dev.fbtools.dbeaver.target.target` e clique em **Set as Active Target Platform** (canto superior direito do editor). Espere a resolução; na primeira vez ela baixa o `debug.ui`.
4. Para rodar o DBeaver com o plugin: clique com o botão direito em `DBeaver com Firebird Tools.launch` (mesmo projeto) e escolha **Debug As > DBeaver com Firebird PSQL Debugger**. Breakpoints no código do plugin funcionam direto.
   - Esse DBeaver usa um workspace separado (`runtime-dbeaver-fbtools`, ao lado do workspace do Eclipse), sem as conexões do DBeaver de uso diário. Crie lá a conexão com o Firebird local.

O `.target` é o mesmo usado pelo `mvnw`, então o que compila na IDE compila no build.

#### Testes na IDE

Os testes ficam em `bundles/dev.fbtools.dbeaver.psqldebug/test/` e são JUnit comum, sem OSGi. O m2e não configura essa pasta sozinho, então faça uma vez por workspace:

1. No projeto do bundle, rode **Maven > Update Project** (Alt+F5). Isso roda o ANTLR e põe o JUnit, o Jaybird e o runtime do ANTLR em *Maven Dependencies*.
2. Em **Project > Properties > Java Build Path > Source**:
   - confira se aparecem `src` e `target/generated-sources/antlr4`, e adicione as que faltarem com *Add Folder…*;
   - adicione `test` e marque **Contains test sources: Yes**;
   - marque **Allow output folders for source folders** e defina o *Output folder* de `test` como `target/test-classes`. Uma pasta de testes precisa de uma pasta de saída própria.
3. Para rodar: botão direito em `test` > **Run As > JUnit Test**. Não use "JUnit Plug-in Test".

O `fbdebug-test.properties` é achado subindo a partir da pasta do projeto. Se o código de `src/` não achar `org.antlr...`, use **PDE Tools > Update Classpath…**.

## Licença

MIT: pode usar, copiar, modificar e redistribuir à vontade, inclusive em projetos comerciais. Veja [LICENSE](LICENSE).

Os componentes de terceiros incluídos (`org.eclipse.debug.ui`, sob EPL 2.0, e o runtime do ANTLR 4, sob BSD 3-Clause) seguem com as licenças originais. Veja [NOTICE](NOTICE).
