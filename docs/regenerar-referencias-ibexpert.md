# Regenerar a Referência do IBExpert

A **Referência do IBExpert** (`samples/export-db/golden/export-db.ibexpert.sql`) é o oráculo da comparação de metadata e de dados (ADR 0008). Ela é gerada à mão, porque o IBExpert não roda na suíte. Regenere sempre que `samples/export-db/create-export-db.sql` mudar em **metadata**; mudar só os dados não exige.

## Passo a passo

1. **Crie o banco sintético** a partir do script versionado, num Firebird onde o IBExpert abra o banco. O IBExpert que a Referência usa (2012.02.21) é antigo; se ele não abrir um banco do Firebird 5, rode antes o script `samples/export-db/create-export-db.sql` num servidor **Firebird 2.5** (o script é o subconjunto comum aos dois) e aponte o IBExpert para esse banco. Use `isql`:

   ```
   isql -user SYSDBA -password <senha> -i samples/export-db/create-export-db.sql
   ```

   O script cria o banco; ajuste o caminho no `CREATE DATABASE` se preciso. **Não** use `create-export-db-fb5.sql`: ele tem os objetos que o 2.5 não gera e fica fora da Referência.
2. **Registre o banco** no IBExpert (*Database > Register Database*), charset `UTF8`.
3. **Extraia**: menu *Tools > Extract Metadata*.
   - Aba *Objects*: **Extract all**.
   - Aba *Data Tables*: marque todas as tabelas.
   - Aba *Options*: marque a gravação de descrições como `COMMENT ON` (o plugin escreve assim; só os privilégios têm diferenças aceitas). Marque os grants.
   - Escolha o arquivo `export-db.ibexpert.sql` e extraia.
4. **Converta a codificação**: o IBExpert grava o arquivo em Windows-1252 apesar do `SET NAMES UTF8`. Converta para UTF-8, sem mudar nenhum texto (por exemplo, no VS Code: *Reopen with Encoding > Windows 1252* e *Save with Encoding > UTF-8*).
5. **Salve** em `samples/export-db/golden/export-db.ibexpert.sql`, sobrescrevendo o anterior, e atualize no `golden/README.md` a data e a versão do IBExpert.
6. **Rode a suíte**: `.\mvnw verify` (na raiz, por inteiro; precisa do Firebird 5 local, ver o `README.md`). A comparação por statement normalizado e por objeto aponta cada divergência.
7. **Trate cada divergência**: ou é defeito do plugin (corrija), ou é uma **Diferença aceita**. Para aceitar, acrescente uma linha em `samples/export-db/golden/allowed-diffs`:

   ```
   TIPO NOME | motivo
   ```

   O motivo é obrigatório (o teste recusa a linha sem ele), cada entrada vale para o objeto inteiro e entradas que nenhuma divergência usa mais devem sair do arquivo.

## Mudou o número de objetos?

`IbexpertReferenceTest` confere a contagem de objetos da Referência por tipo; atualize os números dele junto, se o banco sintético ganhou ou perdeu objetos.
