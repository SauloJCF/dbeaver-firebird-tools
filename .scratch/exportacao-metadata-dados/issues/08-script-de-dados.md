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

- [ ] Ida e volta de dados verde (conteúdo das tabelas idêntico após rodar o script em banco novo).
- [ ] Comparação com a Referência de dados do IBExpert passa, por statement normalizado.
- [ ] Banco `WIN1252` com colunas `NONE` e acentos recria sem perda; aviso aparece (critério 6 da spec).
- [ ] Tabela com milhões de linhas exporta sem estourar a memória.
- [ ] Cancelar interrompe o fetch e remove o arquivo parcial.

## Dependência humana

- Gerar a Referência de dados do IBExpert (ADR 0008).

## Comments

- Do issue 24 do depurador (`fb-psql-debugger`): num servidor com `DataTypeCompatibility = 2.5`, `BOOLEAN` chega como `CHAR(5)` e `DECFLOAT` como `DOUBLE PRECISION`. Ao ler dados para o Script de dados, conferir esses tipos (e `INT128`, `TIME ZONE`) e, se preciso, fixar `SET BIND OF … TO NATIVE` na conexão de leitura. O 24 trata só a Sessão de debug.
- 2026-10-31 (resumo da ADR 0010, proposta, decisão adiada, nada implementado): a Coluna array fica fora do Script de dados. O `SELECT` e o `INSERT` de cada tabela listam as colunas explicitamente, nunca `SELECT *`. As colunas saem de uma lista única do catálogo (`RDB$RELATION_FIELDS` + `RDB$FIELDS.RDB$DIMENSIONS`), que exclui `COMPUTED BY` e Coluna array. Aviso por tabela com as colunas omitidas, no mesmo canal do aviso de colunas `NONE` (ADR 0009), e um comentário antes dos `INSERT`s da tabela. Se a Coluna array for `NOT NULL`, os `INSERT`s da tabela falham ao rodar o script; o aviso diz isso. A ida e volta compara as tabelas sem as Colunas array. Até a ADR ser aceita, a extração falha com `Types.ARRAY: SQL type for this field is not yet supported` (Jaybird) em tabelas com Coluna array: limitação conhecida. Sem teste no Banco sintético atual, já que a `C_VETOR` saiu em 2026-10-31.
