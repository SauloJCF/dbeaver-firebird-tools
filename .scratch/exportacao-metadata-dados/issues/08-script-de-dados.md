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
