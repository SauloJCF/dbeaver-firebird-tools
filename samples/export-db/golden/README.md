# Referência do IBExpert

`export-db.ibexpert.sql` é a saída do IBExpert 2012.02.21 (Extract Metadata, opções padrão, com a aba Data Tables marcada) para o banco criado por `../create-export-db.sql`, gerada em 2026-10-30. Ver ADR 0008.

- O IBExpert gravou o arquivo em Windows-1252 apesar do `SET NAMES UTF8`; ele foi convertido para UTF-8 (só a codificação, nenhum texto foi alterado).
- Contém também os `INSERT`s das tabelas (menos as colunas BLOB e o array), que a comparação de metadata ignora.
- Regenerar sempre que `create-export-db.sql` mudar em metadata. Mudar só os dados não exige.
