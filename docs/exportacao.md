# Exportação de metadata e dados — uso

Plugin `dev.fbtools.dbeaver.export` do DBeaver CE 26.2.2: gera, para um banco Firebird 5, um **Script de metadata** (DDL) e um **Script de dados** (`INSERT`s). Instalação: `instalacao-export.md`. Vocabulário em `../GLOSSARY.md`, decisões em `adr/`.

## Como abrir

Com uma conexão Firebird selecionada no navegador do DBeaver: clique com o botão direito e escolha **Extract Metadata...**, ou use o menu **Firebird Tools > Extract Metadata...**. O wizard tem quatro abas.

| Aba | O que faz |
|---|---|
| **Objects** | Árvore dos objetos disponíveis e lista dos selecionados. **Add >** / **< Remove**, **Add related objects** (traz o que a seleção precisa para rodar: domínios, generators, exceptions, rotinas, tabelas e views de que ela depende), **Extract all** e **Clear**. |
| **Data** | Tabelas cujos dados vão para o Script de dados. **Add related** traz as tabelas referenciadas por FK, transitivamente. |
| **Options** | Opções gerais, de metadata, de grants e de dados (abaixo). As escolhas ficam lembradas para a próxima vez. |
| **Output** | Arquivo do script de metadata (padrão `<conexão>_<AAAAMMDD-HHMM>.sql`, na última pasta usada), codificação e "Limit file size". Os dados vão para um script à parte, `<arquivo>_data.sql`; com limite de tamanho ele se divide em `<arquivo>_data_001.sql`, `_002`… A opção de abrir no editor ao terminar abre só o arquivo de metadata. |

A extração roda em **background**, com progresso na barra do DBeaver. **Cancelar** para a leitura e apaga o arquivo parcial; a interface continua livre durante o trabalho.

## Opções

**Geral**

- *Incluir `CREATE DATABASE`*: escreve só o `CREATE DATABASE`, sem usuário nem senha e sempre com `DEFAULT CHARACTER SET`. Não há `CONNECT`.
- *Sem cabeçalho `SET SQL DIALECT` / `SET NAMES`*: o DBeaver recusa esses comandos no editor; marque para rodar o script lá.

**Metadata**

- *Set generators*: `SET GENERATOR` com o valor atual.
- *Incluir descrições*: `COMMENT ON` (a saída é sempre `COMMENT ON`).
- *Decode domains*: colunas e parâmetros saem com o tipo do domínio (o `CHECK` do domínio não vai).
- *`CREATE OR ALTER`* para views, procedures, functions e triggers.
- *Não usar `SET TERM`*: rotinas terminam em `END;`. Nem o `isql` nem o DBeaver rodam esse formato; use só se a ferramenta de destino separar os statements por conta própria.
- *Always quote identifiers*.
- *`CHARACTER SET` / `COLLATE` em todas as colunas de texto*.
- *`COMPUTED BY` separado*: `ALTER TABLE … ADD` depois do `CREATE TABLE`.
- *`SEQUENCE` no lugar de `GENERATOR`* (com `START WITH` e `INCREMENT` quando preciso).

**Grants**: *Extrair privilégios* e *só os privilégios dos objetos selecionados*.

**Dados**

- Formato de data e de data e hora (padrões do `DateTimeFormatter` do Java; vazio usa o ISO), *prefixo ANSI* (`DATE '…'`, `TIMESTAMP '…'`).
- *Remover espaços finais* e *remover caracteres de controle* dos textos.
- *Extrair BLOBs*: texto inline e binário em `x'…'`. Desmarcado, as colunas BLOB ficam fora do `INSERT` e a extração avisa.
- *`COMMIT WORK` a cada N registros* (padrão 500) e ao fim de cada tabela.

## Fases do Script de metadata

O script sai em **Fases**, na ordem em que o Firebird aceita (ADR 0007, revista pela 0011):

1. collations, BLOB filters, UDFs, domínios, generators/sequences, exceptions, roles e mappings (ordem do código; a spec lista as UDFs e as collations depois das exceptions);
2. tabelas só com chave primária e única; colunas `COMPUTED BY` separadas (só com a opção *`COMPUTED BY` separado*; no padrão ficam no `CREATE TABLE`);
3. views que não leem Rotina, em ordem topológica;
4. **Cabeçalhos vazios** de packages, functions e procedures (resolvem chamadas cíclicas);
5. índices, checks e FKs;
6. corpos das rotinas, intercalados com as views que as leem;
7. triggers (de tabela, de banco e de DDL);
8. `SET GENERATOR`, publication, descrições e grants.

Tudo é lido de `RDB$*` direto (ADR 0007). Rotinas UDR saem como `EXTERNAL NAME`.

### O que a extração avisa

- **Sem ordem possível** (ADR 0011): coluna `COMPUTED BY`, índice de expressão ou `CHECK` que chama Rotina; o script só roda num banco onde a Rotina já exista. E ciclo entre corpo e view (Rotina que lê view que lê a própria Rotina); o script falha nesse ponto.
- **FKs circulares entre tabelas** do Script de dados: rode o script com as FKs dessas tabelas desativadas.
- Colunas com charset `NONE` e conexão com charset diferente do banco (ver abaixo).
- Caracteres que a codificação do arquivo não representa, e colunas BLOB deixadas de fora.

## Script de dados

- `INSERT`s **em ordem de FK** (pais antes de filhas) e lidos **em streaming**: uma tabela de milhões de registros não fica na memória.
- Colunas `COMPUTED BY` ficam de fora e as colunas `GENERATED ALWAYS AS IDENTITY` fazem o `INSERT` sair com `OVERRIDING SYSTEM VALUE`.
- **Triggers disparam nos `INSERT`s.** O script de metadata cria os triggers, e rodar o de dados depois os dispara (o IBExpert grava os dados antes deles). Para carregar os dados como estão, rode o de dados **antes** de criar os triggers, ou desative-os (`ALTER TRIGGER … INACTIVE`) e reative ao fim. A extração não faz isso sozinha.
- **Colunas array** não são suportadas no Script de dados (ADR 0010, ainda proposta): a extração **falha**, dizendo a coluna, e não deixa arquivo parcial. Desmarque essa tabela na aba Data.
- "Limit file size" divide o script em arquivos numerados em sequência, cada um com seu cabeçalho; rode-os na ordem.

## Charset e codificação (ADR 0009)

O arquivo usa por padrão o **charset padrão do banco** (e o `SET NAMES` correspondente); UTF-8 é a alternativa. Para que colunas `NONE` de um banco `WIN1252` cheguem byte a byte e os acentos se preservem, **a conexão do DBeaver deve usar o charset do banco**: a extração usa o que a conexão tem configurado e só **avisa** quando é diferente (com outro charset os acentos saem trocados).

**A senha nunca vai para o arquivo.** Rode o script com o `isql` informando usuário e senha na linha de comando, ou com `CONNECT` por sua conta.

## Rodar o script gerado

- `isql`: `isql -i script.sql` (com `SET TERM`, que é o padrão).
- DBeaver: abra o `.sql` no editor SQL com a conexão destino e execute como script (**Alt+X**). Marque *Sem cabeçalho* na extração.

## Atenção: `-clean` remove o plugin

Como o depurador, o plugin foi instalado por **Install New Software**, e o DBeaver iniciado com `-clean` apaga essa área sem aviso: o menu **Firebird Tools** some. Instale o zip de novo (ADR 0004). Detalhes em `instalacao-export.md`.

## Limites

Fora do escopo: aba IBEBlock, `REINSERT`, "Exclude IBE$/TMP$", `UPDATE … DESCRIBE`, CSV/JSON (use o Data Transfer do DBeaver) e valores de colunas array. Servidores que não sejam Firebird 5 podem funcionar, sem garantia.
