# 07 — Grants, descrições e opções de metadata

Status: ready-for-agent
Blocked by: 06

## O que construir

- Fase de **descrições** (`COMMENT ON`, sempre) e de **grants** (`GRANT … TO`, incluindo roles, `WITH GRANT OPTION`, `GRANT ROLE`, privilégios por coluna e execução de rotinas).
- Opções: Set Generators, incluir descrições, Decode domains, `CREATE OR ALTER`, não usar `SET TERM`, Always quote identifiers, `CHARACTER SET`/`COLLATE` sempre, extrair privilégios, só dos objetos selecionados.
- Aba **Options** completa (grupos Geral, Metadata, Grants), com valores padrão como nos prints do IBExpert, e lembrados entre usos.
- Opções do IBExpert deliberadamente fora: IBEBlock, Exclude IBE$/TMP$, `UPDATE … DESCRIBE`, `COMMENT` opcional.

## Critérios de aceite

- [x] Cada opção tem teste que liga e desliga e confere o efeito no script.
- [x] Comparação com a Referência do IBExpert para grants e descrições passa; `COMMENT ON` consta em `allowed-diffs` como diferença aceita. (Nota: a Referência atual já foi gerada com `COMMENT ON`, então ele não gera diferença e não precisou entrar em `allowed-diffs`; entraram só os grants, ver Comments.)
- [x] Ida e volta verde com grants restritos aos objetos selecionados.

## Dependência humana

- Gerar a nova Referência do IBExpert (ADR 0008).

## Comments

### Implementação (agente, 2026-11-12)

- **Descrições:** `DescriptionCatalog` lê `RDB$DESCRIPTION` de domínios, generators, exceptions, roles, collations, BLOB filters, UDFs (`EXTERNAL FUNCTION`), tabelas, colunas de tabela e de view, views, índices (só os do usuário), procedures, parâmetros de procedure, functions, packages e triggers, sempre como `COMMENT ON`, só dos objetos da seleção. Substitui o `writeDescriptions`, que só cobria domínios e generators. Ficam de fora: objetos dentro de packages, parâmetros de function e a descrição do banco.
- **Grants:** `GrantCatalog` lê `RDB$USER_PRIVILEGES` e gera `GRANT` de tabela/view (privilégios de linha, de coluna, `ALL`, `WITH GRANT OPTION`), `EXECUTE` (procedure, function, package), `USAGE` (exception, generator) e `GRANT role TO` (`WITH ADMIN OPTION`), para usuário, role, `PUBLIC`, view, trigger, procedure, function e package. O destinatário sai com `USER`/`ROLE`, porque o nome sozinho é ambíguo no Firebird 5. Excluídos: privilégios de sistema (`RDB$GRANTOR` nulo) e os do dono sobre o que criou (usuário = quem concede). Privilégios de DDL (`GRANT CREATE TABLE`…) e `USAGE` de domínio/collation/charset (o Firebird 5 nem aceita a sintaxe) não saem; a extração avisa se existirem.
- **Opções novas em `ExtractionOptions`:** `setGenerators` (também liga e desliga o `RESTART WITH` das identities), `descriptions`, `decodeDomains`, `grants`, `grantsOnlySelected`, com `withX` para os testes. Padrão: Set generators, descrições e grants (só dos selecionados) ligados; Decode domains desligado. Os construtores antigos continuam.
- **Decode domains:** coluna de domínio sai com o tipo, o `DEFAULT` e o `NOT NULL` do domínio (o da coluna vence); parâmetro de rotina sai com o tipo, menos `TYPE OF`. O `CHECK` do domínio **não** passa para a coluna (o `VALUE` só existe no domínio) e a extração avisa quais colunas o perdem. Os `CREATE DOMAIN` continuam no script.
- **Aba Options** (`OptionsPage`): grupos Geral (CREATE DATABASE, sem cabeçalho), Metadata (as 9 opções) e Grants (2); valores lembrados nas preferências do bundle. As opções saíram da aba Objects. "Limit file size" e a codificação continuam fora de Options: a codificação é da aba Output e o limite de tamanho é do Script de dados (08).
- **Testes:** `GrantsAndDescriptionsExtractionTest` (17): comparação com a Referência (`DESCRIPTION` e `GRANT`), ida e volta do sintético, de descrições de todos os tipos e de grants de todas as formas (com extras criados no próprio teste, sem mexer no banco sintético nem exigir nova Referência), liga/desliga de cada uma das 13 opções, grants e descrições restritos à seleção e ida e volta com seleção parcial. `mvnw clean verify`: BUILD SUCCESS; 231 testes do depurador e 100 da exportação, 0 falhas.
- **`allowed-diffs`:** 4 entradas de `GRANT`: os 3 `SELECT` de sistema em `RDB$FORMATS`, `RDB$PAGES` e `RDB$ROLES` para `PUBLIC` (o IBExpert 2.5 os lista; o plugin só extrai os do usuário) e `GRANT VENDEDOR TO SYSDBA` (o IBExpert não extrai a pertinência a role). O `StatementNormalizer` dos testes passou a ignorar `USER`/`ROLE` depois do `TO` de um `GRANT`, que é a forma do IBExpert. O teste da comparação confere que essas entradas foram usadas.
- **Dependência humana:** a Referência do IBExpert atual já tem descrições e grants do sintético; só precisa ser gerada de novo se `create-export-db.sql` mudar (não mudou neste issue).
- **Verificado à mão no DBeaver 26.2.2 (agente, 2026-11-12):** build instalado com o p2 director; menu **Firebird Tools > Extract Metadata** abre o wizard (abas Objects, Options, Output). A aba Options mostra os grupos Geral, Metadata e Grants com os padrões esperados; desmarcar "Incluir descrições" e marcar "Decode domains" vale para a extração (sem `COMMENT ON` no arquivo), e as escolhas voltam na vez seguinte (preferências do bundle). Rodei no SAMPLE.FDB, que não tem grants, descrições nem domínios: o conteúdo de `GRANT`/`COMMENT ON`/Decode é coberto pelos testes automáticos, não pelo DBeaver. O script também foi rodado via JDBC (`ExportTestDatabase.run`), não no `isql`.
- **Correções da revisão (agente, 2026-11-12):** grants só saem se o destinatário (role, view, procedure, function, package, trigger) também está na seleção, e os avisos de privilégio não suportado só listam o que está na seleção; Decode domains também mantém o `NOT NULL` do domínio nos parâmetros e avisa o `CHECK` perdido; aviso de descrições que o script não leva (parâmetros de function e rotinas de package); domínios lidos uma vez; `StatementNormalizer` tira `USER`/`ROLE` só como palavra após `TO`; `allowed-diffs` volta a ser conferido (domínios, generators, grants, descrições); `teste-extract.sql` no `.gitignore`. 21 testes novos/ajustados na `GrantsAndDescriptionsExtractionTest`; `mvnw clean verify`: 231 do depurador e 105 da exportação, 0 falhas.
- **Sem teste / sem aviso:** `GRANT ... GRANTED BY` não é emitido (o concedente vira quem roda o script); grants para tipos de destinatário desconhecidos avisam e são pulados; `Selection` com tabela e view de mesmo nome não existe no Firebird, então `relation` olha as duas listas.
