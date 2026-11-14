# Exportação de metadata e dados — instalação

Plugin `dev.fbtools.dbeaver.export` do DBeaver CE 26.2.2. Uso: `exportacao.md`. Vocabulário em `../GLOSSARY.md`, decisões em `adr/`.

## Requisitos

- DBeaver CE **26.2.2** e Firebird **5.0** (ADR 0006). O plugin foi feito para essa versão do DBeaver; outra pode não carregar.
- O zip `firebird-tools-<versão>.zip` (para gerar: "Gerar o zip de instalação" no `../README.md`).

## Instalar

O zip é o mesmo do depurador PSQL e traz as duas features; cada uma se instala e desinstala sozinha. Os passos e as telas são os de `instalacao-psqldebug.md` (passos 1 a 9, com prints); a única diferença é o passo 6: marque **Firebird Metadata Export** (e, se quiser, também **Firebird PSQL Debugger**) em vez de só o depurador. Em resumo:

1. **Help > Install New Software…**, **Add…**, **Archive…**, escolha o zip e clique em **Add**.
2. Marque **Firebird Metadata Export**, avance até a licença, aceite e clique em **Finish**.
3. Confie em `dbeaver.io` (**Select All**, **Trust Selected**) e no artefato **Unsigned** (o plugin é interno e não é assinado).
4. Clique em **Restart Now**.

Depois do reinício aparece o menu **Firebird Tools** na barra de menus, e **Extract Metadata...** no menu de contexto de uma conexão Firebird.

As telas de *Install New Software*, de confiança e de reinício são as mesmas do depurador (`images/` em `instalacao-psqldebug.md`).

## Desinstalar

**Help > Installation Information**, aba *Installed Software*: selecione **Firebird Metadata Export**, clique em **Uninstall…**, confirme e reinicie. O depurador continua instalado.

## Atenção: `-clean` remove o plugin

O DBeaver guarda os plugins instalados desse jeito numa área de cache da pasta `configuration/`. Iniciá-lo com `-clean` (pela linha de comando ou no `dbeaver.ini`) apaga essa área, e o plugin some **sem nenhum aviso**: o menu **Firebird Tools** deixa de aparecer. Para voltar, instale o zip de novo (ADR 0004). Vale para qualquer extensão instalada por "Install New Software".

## Em caso de erro

O log fica no workspace do DBeaver: `%APPDATA%\DBeaverData\workspace6\.metadata\`.
