# Referência do IBExpert

`export-db.ibexpert.sql` é a saída do IBExpert 2012.02.21 (Extract Metadata, com a aba Data Tables marcada e descrições como `COMMENT ON`) para o banco criado por `../create-export-db.sql`, gerada em 2026-11-01. Ver ADR 0008.

- O IBExpert gravou o arquivo em Windows-1252 apesar do `SET NAMES UTF8`; ele foi convertido para UTF-8 (só a codificação, nenhum texto foi alterado).
- O cabeçalho (`CLIENTLIB`, `CREATE DATABASE`) traz caminhos da máquina que gerou; a comparação de metadata ignora esses dois statements.
- Contém também os `INSERT`s das tabelas (menos as colunas BLOB), que a comparação de metadata ignora.
- Gerada já sem a coluna array do banco sintético (ADR 0010); a edição manual de 2026-10-31 deixou de existir.
- Regenerar sempre que `create-export-db.sql` mudar em metadata. Mudar só os dados não exige.
