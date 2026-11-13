# 08 — Script de dados

Status: ready-for-agent
Blocked by: 03

## O que construir

- Aba **Data**: lista de tabelas disponíveis e selecionadas.
- Extração em streaming (`ResultSet` com fetch size, escrita direto no arquivo), sem carregar tabela na memória.
- `INSERT INTO t (cols) VALUES (…)` com literais corretos por tipo, incluindo `BOOLEAN`, `DECFLOAT`, `INT128`, `TIME ZONE`, `NULL`.
- Opções de Dados: formato de data e de datetime, prefixo ANSI, remover espaços finais e caracteres de controle, extrair BLOBs (texto inline, binário em `x'…'`), `COMMIT WORK` a cada N registros (padrão 500).
- "Limit file size" divide o script em vários arquivos, numerados em sequência.
- Charset do script conforme ADR 0009 (conexão de extração no mesmo charset).

## Critérios de aceite

- [x] Ida e volta de dados verde (conteúdo das tabelas idêntico após rodar o script em banco novo).
- [x] Comparação com a Referência de dados do IBExpert passa, por statement normalizado (uma Diferença aceita: frações de segundo em `TIPOS_VARIOS`).
- [x] Banco `WIN1252` com colunas `NONE` e acentos recria sem perda; aviso aparece (critério 6 da spec).
- [x] Tabela com milhões de linhas exporta sem estourar a memória (1 milhão de linhas, ~130 MB de script, com heap de 64 MB).
- [x] Cancelar interrompe o fetch e remove o arquivo parcial.

## Dependência humana

- Gerar a Referência de dados do IBExpert (ADR 0008).

## Comments

- Do issue 24 do depurador (`fb-psql-debugger`): num servidor com `DataTypeCompatibility = 2.5`, `BOOLEAN` chega como `CHAR(5)` e `DECFLOAT` como `DOUBLE PRECISION`. Ao ler dados para o Script de dados, conferir esses tipos (e `INT128`, `TIME ZONE`) e, se preciso, fixar `SET BIND OF … TO NATIVE` na conexão de leitura. O 24 trata só a Sessão de debug.
- 2026-10-31 (resumo da ADR 0010, proposta, decisão adiada, nada implementado): a Coluna array fica fora do Script de dados. O `SELECT` e o `INSERT` de cada tabela listam as colunas explicitamente, nunca `SELECT *`. As colunas saem de uma lista única do catálogo (`RDB$RELATION_FIELDS` + `RDB$FIELDS.RDB$DIMENSIONS`), que exclui `COMPUTED BY` e Coluna array. Aviso por tabela com as colunas omitidas, no mesmo canal do aviso de colunas `NONE` (ADR 0009), e um comentário antes dos `INSERT`s da tabela. Se a Coluna array for `NOT NULL`, os `INSERT`s da tabela falham ao rodar o script; o aviso diz isso. A ida e volta compara as tabelas sem as Colunas array. Até a ADR ser aceita, a extração falha com `Types.ARRAY: SQL type for this field is not yet supported` (Jaybird) em tabelas com Coluna array: limitação conhecida. Sem teste no Banco sintético atual, já que a `C_VETOR` saiu em 2026-10-31.
- 2026-11-13 (implementação): `DataExtractor` (INSERTs em streaming, fetch 1000, lista explícita de colunas lida de `RDB$`, sem `COMPUTED BY`; `OVERRIDING SYSTEM VALUE` com identity `ALWAYS`), `DataLiterals` (literais por tipo), `DataOptions` e `DataFile` (grava e divide). Aba **Data** no wizard, grupo **Dados** em Options, "Limit file size" em Output; o job grava o script de dados à parte, em `<arquivo>_data.sql`. Testes: `DataLiteralsTest` (sem banco) e `DataExtractionTest` (Referência, ida e volta com FKs ativas, tipos do Firebird 5, opções, `COMMIT WORK`, divisão, WIN1252/`NONE`, cancelamento, 1 milhão de linhas). `.\mvnw clean verify`: BUILD SUCCESS, suíte inteira verde. Rodar o teste do milhão com `-DargLine=-Xmx64m` também passou. Dependência humana: nenhuma bloqueia (a Referência de dados já está em `golden/`).
- Decisões: (1) **Diferença aceita** `DATA TIPOS_VARIOS`: o IBExpert trunca as frações de segundo (`TIME` `.9999`→`.999`, `TIMESTAMP` sem fração); o plugin mantém o valor. Como a entrada vale para a tabela inteira, um teste à parte confere que a única divergência é essa. (2) O comparador de testes não ganhou chave para `INSERT` (o `ScriptComparatorTest` exige `INSERT` ignorado); a comparação de dados é feita no `DataExtractionTest`. (3) **`SET BIND OF BOOLEAN/DECFLOAT/INT128/TIME ZONE TO NATIVE`** na conexão de leitura: o servidor local está com `DataTypeCompatibility` antigo (DECFLOAT chegava como DOUBLE, INT128 estourava, o fuso sumia), como previa o comentário do issue 24. O erro do comando é ignorado (Firebird 3). (4) Colunas BLOB só entram com a opção ligada; desligada, saem do `INSERT` como no IBExpert e a extração avisa. (5) Com limite de tamanho, todos os arquivos levam número (`_001`…); cada um começa com o próprio cabeçalho e termina num `COMMIT WORK`. (6) Coluna array: nada feito (ADR 0010 segue proposta); a extração falha com o erro do Jaybird.
- Pendências para outros issues: **ordem de FK** (issue 09): a ordem do script é a da lista (alfabética no wizard); sem o 09, tabelas filhas antes das pais falham com as FKs ativas. **Triggers**: rodar o script de dados depois do de metadata dispara os triggers nos `INSERT`s (o IBExpert grava os dados antes deles); o teste de ida e volta desativa os triggers antes de carregar. Vale decidir no 09 ou no 10 se o script de dados ganha `ALTER TRIGGER ... INACTIVE`/`ACTIVE` opcional. Wizard (UI): compila, mas não foi aberto no DBeaver (passo humano).
