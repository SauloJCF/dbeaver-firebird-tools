# 0010 — Colunas array: valores fora do Script de dados

Status: proposto — 2026-10-31 · decisão adiada, nada implementado

## Contexto

O Firebird aceita colunas com dimensões (`INTEGER [1:5]`), declaradas na própria coluna ou num domínio: a Coluna array. O Jaybird não lê arrays pelo JDBC. Um `SELECT *` numa tabela com uma delas falha com `SQL Error [0A000]: Types.ARRAY: SQL type for this field is not yet supported`. O erro apareceu ao abrir `TIPOS_VARIOS` (coluna `C_VETOR`) do Banco sintético no DBeaver.

Gravar também não tem caminho em SQL. Pela documentação do Firebird (não conferido no Firebird 5 local), o DSQL e o PSQL não têm literal nem construtor de array, e o valor só se grava pela API (`isc_array_put_slice`). A mesma documentação desaconselha o tipo.

A Referência do IBExpert omite as colunas array dos `INSERT`s sem avisar (e as BLOB também, nas opções padrão). O autor não usa arrays hoje e quer rever o assunto mais tarde.

## Decisão (proposta)

Padrão quando esta ADR for aceita (opção 1, omitir):

- O Script de dados omite as Colunas array. O `SELECT` e o `INSERT` de cada tabela listam as colunas explicitamente, nunca com `SELECT *`: a coluna fica fora da leitura, e não só do `INSERT`.
- As colunas de cada tabela saem de uma única lista, tirada do catálogo (`RDB$RELATION_FIELDS` + `RDB$FIELDS.RDB$DIMENSIONS`). Ela exclui `COMPUTED BY` e Coluna array e devolve as omitidas para o aviso. É o único ponto que muda se a opção 2 vier.
- A tabela continua no Script de dados com as demais colunas. No banco recriado, a Coluna array fica `NULL`.
- O aviso é por tabela, com as colunas omitidas, e vai no mesmo canal do aviso de colunas `NONE` (ADR 0009). O script também ganha um comentário antes dos `INSERT`s da tabela. O comparador descarta comentários, então isso não cria diferença com a Referência do IBExpert.
- Se a Coluna array for `NOT NULL`, os `INSERT`s da tabela falham ao rodar o script. O aviso diz isso, e o plugin não tenta contornar.
- O Script de metadata não é afetado: a Coluna array e o domínio array saem com as dimensões (`RDB$FIELD_DIMENSIONS`). Ler o catálogo não esbarra no limite do Jaybird.

Até esta ADR ser aceita, nada disso é implementado, e a Extração falha com o erro do Jaybird ao chegar aos dados de uma tabela com Coluna array. É uma limitação conhecida, aceitável enquanto nenhum banco usado tiver arrays.

## Alternativas

- **Suportar os valores (opção 2, adiada)**: a leitura teria de passar pela API nativa (`isc_array_lookup_bounds`/`isc_array_get_slice`, ou o `getSlice` da API nova), que o Jaybird não expõe pelo JDBC. Isso exige um binding próprio ao `fbclient` ou ao protocolo, fora da conexão do DBeaver. Ler elemento a elemento em SQL (`C_VETOR[1]`) talvez sirva, se o DSQL do Firebird 5 aceitar (não conferido). O custo maior é a gravação: sem literal de array, os valores não cabem num `INSERT` que o `isql` rode, e a carga teria de ser feita pelo plugin, não por um script. Contradiz ADR-0007 (A exportação lê as tabelas `RDB$*` direto), que tem o `isql` e o DBeaver como alvo do script. Só vale reabrir se um banco real precisar recriar os valores.
- **Abortar a Extração ao achar uma Coluna array**: evita a perda silenciosa, mas bloqueia todas as outras tabelas por causa de um tipo que o próprio Firebird desaconselha.
- **Pular a tabela inteira**: perde todas as outras colunas e não salva o array.
- **Omitir em silêncio, como o IBExpert**: o script parece completo e não está. O aviso custa pouco.

## Consequências

- Uma tabela com Coluna array não é recriada com o mesmo conteúdo: o critério 4 da spec vale só para as outras colunas. A Ida e volta de dados (ADR 0008) lê pelo mesmo JDBC e compara as tabelas sem as Colunas array.
- Para essas tabelas, o Script de dados fica igual à Referência do IBExpert, que já omite arrays. Nenhuma Diferença aceita é necessária.
- A `C_VETOR` saiu do Banco sintético (`samples/export-db/create-export-db.sql`). Com isso, a DDL de Coluna array (`INTEGER [1:5]`) no Script de metadata fica sem teste, tanto na comparação com a Referência do IBExpert quanto na Ida e volta. O filtro da opção 1 também. Sem esse teste, nada impede a issue 03 de emitir a coluna sem as dimensões, como escalar. Nesse caso, um banco real com array seria recriado com o tipo errado, em silêncio.
- A Referência do IBExpert (`samples/export-db/golden/export-db.ibexpert.sql`) foi gerada antes da remoção e ainda tem `C_VETOR INTEGER [1:5]` no `CREATE TABLE TIPOS_VARIOS`. Ela precisa ser regenerada (ADR 0008) antes de a issue 03 comparar tabelas. Os `INSERT`s dela não mudam. Não resolver com `allowed-diffs`: uma entrada para `TABLE TIPOS_VARIOS` esconderia qualquer outra diferença justamente na tabela que concentra os tipos.
- Ao aceitar esta ADR, a Coluna array volta ao Banco sintético numa tabela própria, só com chave e arrays: uma coluna com dimensões declaradas nela e outra por domínio array. Assim, a DDL, a Referência do IBExpert e o filtro voltam a ter teste, e `TIPOS_VARIOS` continua abrindo no DBeaver. Isso exige outra regeneração da Referência.

## Quando reabrir

- Aceitar a opção 1 quando um banco que precise do Script de dados tiver Coluna array, ou quando a cobertura da DDL de array fizer falta. É uma issue pequena: a lista de colunas, o aviso e a tabela no Banco sintético.
- Reavaliar a opção 2 só se for preciso recriar os valores. Primeiro passo: conferir no Firebird 5 local se o DSQL lê e grava elementos de array.
