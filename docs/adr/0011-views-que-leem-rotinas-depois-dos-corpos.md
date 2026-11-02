# 0011 — Views que leem rotinas saem junto dos corpos, não antes dos Cabeçalhos vazios

Status: aceito — 2026-11-02 · já implementado na issue 05 · substitui, na ADR 0007, só a ordem das Fases de views, Cabeçalhos vazios e corpos

## Contexto

A ADR 0007 pôs todas as views, em ordem topológica, antes dos Cabeçalhos vazios, e todos os corpos (`ALTER`) depois de índices e FKs. A ideia era que o Cabeçalho vazio bastasse para qualquer objeto que use a Rotina.

Na issue 05 (rotinas, functions e packages), o Firebird 5 local mostrou que não basta:

- Uma view que lê uma procedure (`SELECT … FROM SP(...)`) não pode vir antes da procedure existir. Se vier depois do Cabeçalho vazio e outra view for criada sobre ela, o `ALTER PROCEDURE` do corpo falha com `cannot delete DOMAIN RDB$n`. A coluna da view herda o domínio anônimo do parâmetro de saída da procedure, a view de cima prende esse domínio, e o `ALTER` precisa recriá-lo.
- A dependência de uma view em function ou em rotina de package não fica gravada na view. O Firebird a grava como dependência de tipo 3 (campo) do domínio anônimo da coluna da view que chama a function. Quem lê só as dependências de tipo 1 (view) não vê que a view usa a function.
- Coluna `COMPUTED BY`, índice de expressão e `CHECK` que chamam Rotina pertencem à tabela e saem com ela (ou na Fase de índices e checks), sem posição no script que o Firebird aceite: antes do Cabeçalho vazio a Rotina não existe, e um objeto que já usa a Rotina pode prender o domínio de que o `ALTER` do corpo precisa, como no caso da view.

## Decisão

Ordem das Fases do Script de metadata a partir da issue 05. O resto da lista da ADR 0007 (exceptions, UDFs, collations, triggers, `SET GENERATOR`, descrições, grants) continua valendo nas posições de lá:

1. Domínios.
2. Generators.
3. Tabelas, só com PK e UNIQUE.
4. Colunas `COMPUTED BY` separadas, se a opção estiver ligada.
5. Views que não leem Rotina, nem direta nem indiretamente, em ordem topológica.
6. Cabeçalhos vazios: o header inteiro dos packages, depois functions, depois procedures.
7. Índices, checks e FKs.
8. Corpos (`ALTER`) intercalados com as **views que leem rotinas**, numa só ordem de dependência:
   - a view vem depois do corpo de cada Rotina que ela lê (procedure, function ou package);
   - a view vem depois das views que ela lê;
   - o corpo que lê uma dessas views vem depois dela;
   - fora isso, os corpos vêm primeiro, em ordem alfabética. Passos seguidos do mesmo tipo saem num bloco só (um `SET TERM` para vários corpos).

Regras que a acompanham:

- Uma view "lê rotina" se tem dependência direta de tipo 1 em procedure (5), function (15) ou package (18), ou dependência de tipo 3 pelo domínio de alguma coluna. Também conta se ela lê outra view que lê rotina: a propriedade se propaga pela ordem topológica.
- Ciclo corpo ↔ view (a Rotina lê a view que lê a própria Rotina): nenhuma ordem é aceita pelo Firebird. A extração quebra o ciclo num ponto qualquer, de forma estável, e o script falha ao rodar nesse ponto. A extração avisa que o ciclo existe.
- Coluna `COMPUTED BY`, índice de expressão e `CHECK` que chamam Rotina continuam na posição de sempre. A Extração avisa, com tabela e Rotina, que o script só roda num banco onde a Rotina já exista.

## Alternativas descartadas

- **Manter a ordem da ADR 0007 (todas as views antes dos Cabeçalhos vazios)**: a view que lê procedure não compila sem a procedure.
- **Todas as views depois dos Cabeçalhos vazios, antes dos corpos**: falha no `ALTER PROCEDURE` com `cannot delete DOMAIN RDB$n` sempre que houver view sobre view que lê a procedure.
- **Todas as views depois de todos os corpos**: quebra o corpo que lê uma view (o `ALTER` não compila sem ela). Procedure que lê view é comum; não dá para tirar as views de vez do caminho dos corpos.
- **`CREATE` da Rotina já com o corpo, sem Cabeçalho vazio, em ordem topológica geral**: perde a solução dos ciclos entre Rotinas, que é o motivo do Cabeçalho vazio (ADR 0007).
- **Mover `COMPUTED BY`, índice de expressão e `CHECK` com Rotina para depois dos corpos**: a coluna calculada muda a forma da tabela que views e Rotinas já leram, e esse caminho não foi medido. Fica o aviso até aparecer um caso real.

## Consequências

- A Fase de views é dividida em duas. Quem lê o script vê um bloco `Views` antes dos Cabeçalhos vazios e blocos `Views that read routines` entre os blocos `Routine bodies`. A comparação com a Referência do IBExpert (ADR 0008) compara por statement e por objeto, então a ordem diferente da do IBExpert não vira Diferença aceita.
- O critério de aceite 1 da spec ("roda de ponta a ponta") tem duas exceções conhecidas e avisadas: o ciclo corpo ↔ view e o objeto de tabela que chama Rotina. A Ida e volta não deve usar nenhum dos dois no Banco sintético, ou deve esperar o erro.
- As consultas de catálogo precisam olhar a dependência de tipo 3 pelo domínio da coluna, além da de tipo 1. Se uma versão nova do Firebird mudar onde grava essa dependência, a separação das views falha em silêncio: a view vai para antes dos Cabeçalhos vazios e o script quebra. Um teste com view que chama function protege disso.
- Triggers, que a issue 06 traz, também leem views e Rotinas. Elas devem sair depois do último passo da Fase 8, não entre os corpos.

## Quando reabrir

- Se o Firebird passar a aceitar `ALTER` de corpo com dependentes de domínio anônimo, a Fase 8 pode voltar a ser "corpos" e as views que leem rotinas voltam a ficar todas antes deles.
- Se um banco real tiver coluna `COMPUTED BY`, índice de expressão ou `CHECK` com Rotina, medir de novo a ordem (por exemplo, `ALTER TABLE … ADD` depois dos corpos) antes de aceitar o aviso como solução.
