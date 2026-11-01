# Área: Interpretador do Depurador PSQL

O núcleo sem UI: gramática ANTLR4 da camada procedural, execução no cliente e conversa com o Firebird 5 só para Fatias DML e Avaliações de expressão. A Debug UI consome este código.

## Onde

- Gramática em `bundles/*/antlr4/**/Psql{Lexer,Parser}.g4`; código do Interpretador, do Pré-check e do Package temporário no bundle `dev.fbtools.dbeaver.psqldebug`.
- Ler antes: `GLOSSARY.md` (seção Depurador PSQL), ADRs 0001 a 0005.

## Regras do domínio

- **Toda expressão é avaliada no servidor**: `SELECT <expr> FROM RDB$DATABASE` com `CAST(? AS <tipo>)`. O Modo Fast (avaliação no cliente) está fora do MVP.
- **Fatia DML é texto opaco**: o Interpretador só troca `:var` por `?` e identifica os alvos de `INTO`; o interior é do servidor.
- Mudou a gramática: `.\mvnw clean verify` (constantes de token ficam velhas no build incremental).

## Testes

O teste certo é o **Teste diferencial** (ADR 0005): a mesma Rotina roda no servidor e no Interpretador, cada uma em transação com rollback, comparando saídas, linhas de `SUSPEND`, exceção e estado das tabelas. A saída esperada vem do servidor.

## Fronteiras

UI, launch e `plugin.xml` são da área Debug UI; poms, features e p2, da área Build.
