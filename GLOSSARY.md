# Firebird Tools para DBeaver — Contexto

Conjunto de plugins do DBeaver CE para Firebird 5 (ADR 0006), ferramenta pessoal que substitui recursos do IBExpert:

- **Depurador PSQL** (`dev.fbtools.dbeaver.psqldebug`): depura código PSQL contra um banco local de desenvolvimento.
- **Exportação** (`dev.fbtools.dbeaver.export`): gera scripts de metadata e de dados do banco.

## Depurador PSQL

**Rotina**
Unidade de PSQL que pode ser depurada: procedure executável, procedure selecionável, `EXECUTE BLOCK`, function, rotina de package ou trigger de tabela.
_Evitar_: "objeto", "SP" quando o termo se aplica a qualquer um dos tipos.

**Sessão de debug**
Uma execução de uma Rotina sob o debugger, do launch até terminar ou ser abortada. Tem uma conexão dedicada e uma única transação.

**Launch**
Início de uma Sessão de debug a partir do DBeaver: escolhe a Rotina, preenche parâmetros, isolamento e "commit ao terminar" num diálogo. É a única forma de iniciar uma sessão (não existe attach).

**Interpretador**
Componente do plugin que percorre o AST da Rotina e executa a lógica procedural no cliente, mandando ao servidor apenas Fatias DML e Avaliações de expressão.

**Statement procedural**
Statement que o Interpretador executa por conta própria: `DECLARE`, atribuição, `IF`, `WHILE`, `FOR SELECT`, `BEGIN/END`, `WHEN`, `EXCEPTION`, `SUSPEND`, `LEAVE`, `CONTINUE`, cursores, chamadas de sub-rotina.

**Fatia DML**
Trecho de `SELECT`/`INSERT`/`UPDATE`/`DELETE`/`MERGE` embutido na Rotina, tratado como texto opaco. O parser só localiza as referências `:var` (que viram parâmetros `?`) e os alvos de `INTO`; o servidor executa o resto.

**Avaliação de expressão**
Cálculo de uma expressão PSQL enviando `SELECT <expr> FROM RDB$DATABASE` ao servidor, com cada variável como `CAST(? AS <tipo declarado>)`. Mesmo mecanismo para condições, atribuições, watches e variáveis editadas.

**Modo Careful**
Modo de execução em que toda expressão passa por Avaliação de expressão no servidor. Único modo do MVP. (Nome herdado do IBExpert.)

**Modo Fast**
Modo futuro em que atribuições simples e condições booleanas são avaliadas no cliente. Fora do MVP.

**Package temporário de debug**
Package `DBG$...` criado no banco durante a Sessão de debug para hospedar as sub-rotinas internas (`DECLARE PROCEDURE/FUNCTION`) da Rotina, para que expressões que as chamam possam ser avaliadas no servidor. Removido ao fim da sessão; restos são varridos ao abrir a próxima. Sub-rotina que usa variáveis da Rotina não entra no package (ADR 0003).

**Step into elegível**
Chamada em que o step into entra na Rotina chamada: `EXECUTE PROCEDURE`, `FOR SELECT … FROM sp(...)` com a procedure como única fonte, e `x = F(...)` com a chamada como expressão inteira.

**Executado no servidor**
Chamada de Rotina que fica dentro de uma Fatia DML (ex.: `INSERT … SELECT F(x)`) e por isso não é alcançável por step into. A linha mostra esse aviso.

**Trigger isolada**
Depuração de uma trigger de tabela fora de um DML: `NEW.*`, `OLD.*` e `INSERTING`/`UPDATING`/`DELETING` são entradas do diálogo de launch. Triggers nunca são alcançadas por step into a partir de DML.

**Pré-check**
Varredura do AST antes do launch que lista construções não suportadas (com linha e coluna) e recusa o launch se houver alguma.

**Fonte do banco**
O texto da Rotina gravado nos metadados (`RDB$PROCEDURE_SOURCE` etc.). É sempre o código depurado; o launch é bloqueado se o editor tiver alterações não salvas. Exceção: `EXECUTE BLOCK` não existe no banco e é depurado a partir do texto do editor SQL.

**Teste diferencial**
Teste que executa a mesma Rotina no servidor (execução nativa) e no Interpretador, cada uma em transação própria com rollback, e compara saídas, linhas de `SUSPEND`, exceção e estado das tabelas.

## Exportação de metadata e dados

**Extração**
Uma execução da exportação, do clique em "Extrair" até o arquivo pronto, cancelada ou com erro. Roda em job de background, com progresso e cancelamento.

**Script de metadata**
Arquivo `.sql` com o DDL dos objetos escolhidos, em Fases. Feito para o `isql` e o DBeaver (`SET TERM` por padrão); nunca contém senha.

**Script de dados**
Arquivo `.sql` com `INSERT`s das tabelas escolhidas, em ordem de FK, com `COMMIT WORK` a cada N registros. Lido em streaming.

**Coluna array**
Coluna cujo tipo tem dimensões, declaradas na própria coluna ou no domínio que ela usa (ex.: `INTEGER [1:5]`). Ver ADR 0010.
_Evitar_: "vetor", "campo array".

**Fase**
Bloco do Script de metadata que reúne um tipo de objeto (domínios, generators, tabelas, views, cabeçalhos de rotinas, índices e FKs, corpos, triggers, descrições, grants…). A ordem das Fases e a ordem topológica dentro delas vêm de `RDB$DEPENDENCIES` (ADR 0007).

**Cabeçalho vazio**
`CREATE PROCEDURE/FUNCTION/PACKAGE` só com a assinatura e corpo vazio, emitido numa Fase anterior à dos corpos. Resolve chamadas cíclicas entre rotinas.

**Objetos relacionados**
Objetos de que um objeto selecionado depende (domínios, generators, exceptions, rotinas). "Add related objects" os inclui na seleção para o script rodar sem faltar nada.

**Charset do script**
Codificação do arquivo e `SET NAMES` do script. Por padrão, o charset padrão do banco (ADR 0009).

**Banco sintético**
Banco Firebird criado do zero por um script versionado, só para os testes da Exportação, nunca copiado de banco de cliente. É a entrada da Referência do IBExpert e da Ida e volta.
_Evitar_: "banco de exemplo" (é o do depurador), "banco de teste".

**Referência do IBExpert**
Saída do IBExpert para o Banco sintético, gerada à mão e versionada em `golden/`. Oráculo só para o subconjunto comum ao Firebird 2.5 e ao 5 (ADR 0008).

**Diferença aceita**
Divergência deliberada entre a saída do plugin e a Referência do IBExpert (ex.: `COMMENT ON` no lugar de `UPDATE … DESCRIBE`), registrada em `allowed-diffs`.

**Ida e volta**
Teste que extrai o metadata de um banco, roda o script num banco vazio, extrai de novo e compara os dois scripts (e o conteúdo das tabelas, para dados).
