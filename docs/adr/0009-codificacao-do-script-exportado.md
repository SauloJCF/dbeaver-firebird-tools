# 0009 — O script exportado usa o charset padrão do banco

Status: aceito — 2026-10-28

## Contexto

Bancos Firebird antigos (o do autor é `WIN1252`) frequentemente têm colunas e domínios `CHARACTER SET NONE`, onde o aplicativo gravou bytes WIN1252 sem o servidor saber. Exportar por uma conexão UTF-8 faz o servidor devolver esses bytes crus, que viram texto inválido ou erro de transliteração: o UTF-8 perde dados em silêncio. Além disso, um `CREATE DATABASE` sem `DEFAULT CHARACTER SET` cria o banco novo com charset `NONE`, e colunas sem charset explícito herdam isso.

## Decisão

- O arquivo é escrito na codificação do charset padrão do banco, com `SET NAMES <charset>;` no início. A conexão de extração usa esse mesmo charset, então colunas `NONE` e do charset do banco chegam e saem byte a byte.
- O diálogo oferece "Codificação do arquivo", com UTF-8 como alternativa. O plugin avisa se houver colunas `NONE` ou caracteres sem correspondência.
- O `CREATE DATABASE` sempre leva `DEFAULT CHARACTER SET` (e a collation padrão, se houver). `CHARACTER SET`/`COLLATE` por coluna são emitidos quando diferem do padrão, ou sempre, pela opção correspondente.
- Nome padrão do arquivo: `<conexão>_<AAAAMMDD-HHMM>.sql`. O último diretório usado é lembrado.

## Alternativas descartadas

- **UTF-8 sempre**: perde dados em colunas `NONE` e foge do que o IBExpert faz.
- **Detectar e converter automaticamente**: esconderia a decisão de quem sabe qual era o charset real.

## Consequências

- O editor do DBeaver precisa abrir o arquivo na mesma codificação (preferência do editor), senão os acentos aparecem trocados. O `isql` lê o `SET NAMES` e não tem esse problema.
- A comparação com a referência do IBExpert (ADR 0008) fica byte a byte também na codificação.
