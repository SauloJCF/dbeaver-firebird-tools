# Depurador PSQL — instalação e uso

Plugin `dev.fbtools.dbeaver.psqldebug` do DBeaver CE 26.2.2 para depurar Rotinas PSQL do Firebird 5. Como ele funciona por dentro: `como-funciona.md`. Vocabulário em `../GLOSSARY.md`, decisões em `adr/`.

## Instalação

### Requisitos

- DBeaver CE **26.2.2**. O plugin traz um `org.eclipse.debug.ui` fixado para a plataforma Eclipse dessa versão (ADR 0004); outra versão do DBeaver pode não carregar.
- Firebird 5 local, com a conexão já criada no DBeaver.
- Para compilar o zip (ver "Gerar o zip de instalação" no `README.md`): JDK 21 no `JAVA_HOME`.

### Instalar

1. Obtenha o zip `firebird-tools-<versão>.zip`. Não descompacte.
2. No DBeaver: **Help > Install New Software…**

   <img src="images/help-menu-dbeaver.png" alt="Menu Help do DBeaver com Install New Software destacado" width="350">

3. Na janela *Install*, clique em **Add…**.

   <img src="images/install-new-software-window.png" alt="Janela Install com o botão Add destacado" width="700">

4. Em *Add Repository*, clique em **Archive…** e escolha o zip.

   <img src="images/adding-archive.png" alt="Diálogo Add Repository com o botão Archive e o zip escolhido no seletor de arquivos" width="700">

5. Com o caminho `jar:file:...zip!/` preenchido em *Location*, clique em **Add**. O campo *Name* pode ficar vazio.

   <img src="images/confirming-add.png" alt="Diálogo Add Repository com o zip em Location e o botão Add destacado" width="475">

6. Marque **Firebird PSQL Debugger** e clique em **Next** até a licença. Aceite a licença e clique em **Finish**.

   <img src="images/selecting-software.png" alt="Lista de software com Firebird PSQL Debugger marcado" width="700">

7. Pode aparecer *Trust Authorities*, pedindo confiança em `https://dbeaver.io`, que é o site de atualizações do próprio DBeaver. Clique em **Select All** e depois em **Trust Selected**.

   <img src="images/trusting-software.png" alt="Diálogo Trust Authorities com dbeaver.io, botões Select All e Trust Selected" width="600">

8. Em *Trust Artifacts*, o DBeaver avisa que o plugin não é assinado (é interno). Marque a linha **Unsigned** e clique em **Trust Selected**.

   <img src="images/trust-artifacts.png" alt="Diálogo Trust Artifacts com a linha Unsigned marcada e o botão Trust Selected" width="600">

9. Aceite reiniciar o DBeaver com **Restart Now**.

   <img src="images/restart-now.png" alt="Diálogo Software Updates com o botão Restart Now" width="500">

Depois do reinício aparece o menu **PSQL Debug** na barra de menus. Veja "Utilização" abaixo.

### Desinstalar

**Help > Installation Information**, aba *Installed Software*: selecione **Firebird PSQL Debugger**, clique em **Uninstall…**, confirme e reinicie o DBeaver.

### Atenção: `-clean` remove o plugin

O DBeaver guarda os plugins instalados desse jeito numa área de cache da pasta `configuration/`. Iniciar o DBeaver com `-clean` (pela linha de comando ou no `dbeaver.ini`) apaga essa área, e o plugin some **sem nenhum aviso**: o menu **PSQL Debug** deixa de aparecer. Para voltar, instale o zip de novo (ADR 0004, consequências). Isso vale para qualquer extensão instalada por "Install New Software" no DBeaver.

### Em caso de erro

O log fica no workspace do DBeaver: `%APPDATA%\DBeaverData\workspace6\.metadata\`.

- `.log`: erros da plataforma Eclipse, como falha ao carregar o plugin ou o `org.eclipse.debug.ui`.
- `dbeaver-debug.log`: log do próprio DBeaver. As cópias antigas ficam como `dbeaver-debug-<número>.log`.

Ao relatar um problema, mande esses arquivos junto com a Rotina e o passo que falhou. Os avisos da Sessão de debug (chamadas executadas no servidor, erros do Firebird) aparecem também no console da sessão, na view **Console**.

## Utilização

### 1. Abrir a Rotina e marcar breakpoints

Abra a procedure, function ou trigger pelo navegador do DBeaver e vá para a pasta **Source** do editor. O editor precisa estar salvo: o launch é recusado com alterações pendentes.

Para alternar um breakpoint numa linha, use um destes:

- duplo-clique na margem esquerda (ruler) da linha;
- **Ctrl+Shift+B** com o cursor na linha;
- botão direito no ruler > **Alternar Breakpoint**.

Os breakpoints valem só enquanto o editor da Rotina está aberto e não são salvos.

### 2. Iniciar a Sessão de debug

Com o editor da Rotina ativo, escolha **Depurar Rotina** no menu de contexto do texto ou no menu **PSQL Debug**. Na imagem, o ponto azul na margem é um breakpoint.

<img src="images/source-procedure.png" alt="Pasta Source de uma procedure com um breakpoint na margem e o item Depurar Rotina no menu de contexto" width="700">

No menu **PSQL Debug**, **Depurar Rotina** fica inativo quando nenhum editor de Rotina Firebird está ativo.

<img src="images/system-menu.png" alt="Menu PSQL Debug com Depurar Rotina inativo e Abrir Package" width="170">

### 3. Informar os parâmetros

No diálogo de launch:

- **Parâmetros de entrada:** para cada parâmetro, escolha **Valor** e digite o valor, ou escolha **NULL**. Um parâmetro com valor padrão na declaração também oferece **Padrão**. O servidor converte o texto para o tipo do parâmetro, como num `CAST`. Numa trigger, o diálogo pede os valores de `NEW`/`OLD` e o evento.
- **Isolamento:** `READ COMMITTED` ou `SNAPSHOT`.
- **Commit ao terminar:** desmarcado, tudo o que a Rotina fizer é desfeito no fim da sessão (rollback). Marque só se quiser que as alterações fiquem no banco.

<img src="images/debug-pre-window.png" alt="Diálogo de launch com o parâmetro P_LIMITE, o isolamento READ COMMITTED e a opção Commit ao terminar" width="525">

### 4. Depurar

O DBeaver troca para a perspectiva **Debug** e a execução para no primeiro breakpoint alcançado. Sem breakpoint, a Rotina roda até o fim e o resultado sai no console.

<img src="images/in-debug.png" alt="Perspectiva Debug com a pilha na view Debug, a linha atual destacada no editor, a view Variables e o console da sessão" width="900">

- **Debug** (à esquerda): a pilha de chamadas. Clique num nível para ver a linha e as variáveis dele.
- **Editor:** a seta na margem marca a próxima linha a executar.
- **Variables:** parâmetros, variáveis locais e de saída. Para alterar um valor durante a pausa, clique na coluna *Value* e digite.
- **Breakpoints:** lista, desativa e remove breakpoints.
- **Expressions:** watches, expressões PSQL (por exemplo `TOTAL * 2`) avaliadas no servidor a cada pausa. Para criar um watch, clique em *Add new expression* na própria view. O watch não altera o banco.
- **Console:** parâmetros usados, chamadas executadas no servidor, erros e o desfecho da sessão (commit ou rollback).

Atalhos, os mesmos do Eclipse:

| Tecla | Ação |
| ----- | ---- |
| F5 | Step into: entra na Rotina chamada por `EXECUTE PROCEDURE` ou em sub-rotina |
| F6 | Step over: executa a linha atual |
| F7 | Step return: termina a Rotina atual e para em quem a chamou |
| F8 | Resume: segue até o próximo breakpoint ou o fim |

Para encerrar a sessão antes do fim, use o botão **Terminate** (quadrado vermelho) da barra de ferramentas.

Chamadas dentro de um comando SQL (por exemplo, uma function num `SELECT`) rodam no servidor sem pausa, e a linha ganha um aviso na margem.

### Rotinas de package

O DBeaver não mostra as packages do Firebird. Use **PSQL Debug > Abrir Package…**, com a conexão selecionada no navegador. O corpo da package abre num console só de leitura, onde você marca os breakpoints e usa **Depurar Rotina** com o cursor na rotina desejada.

