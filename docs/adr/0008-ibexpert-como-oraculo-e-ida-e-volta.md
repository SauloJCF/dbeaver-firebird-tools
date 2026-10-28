# 0008 — IBExpert como oráculo, mais teste de ida e volta

Status: aceito — 2026-10-28

## Contexto

O critério da exportação é "tem os mesmos recursos do IBExpert", sem precisar ser idêntico, e com objetos do Firebird 5 que o IBExpert (compatível só com o 2.5) não conhece. Nenhuma expectativa de DDL deve ser escrita à mão (mesma lógica da ADR 0005), e o IBExpert não roda no build.

## Decisão

- **Oráculo**: a saída do IBExpert para um banco de teste sintético (`samples/`, nunca o banco de um cliente) é gerada uma vez, à mão, com as opções padrão, e versionada como referência (`golden/`). Vale só para o subconjunto comum ao Firebird 2.5 e ao 5.
- **Comparação**: por statement, não por texto. Os dois scripts são divididos em statements (respeitando `SET TERM`), normalizados (espaços, quebras, caixa de palavras-chave, aspas) e comparados **por objeto**. A ordem entre fases não conta. Diferenças aceitas de propósito (por exemplo `COMMENT ON` no lugar de `UPDATE … DESCRIBE`, `SEQUENCE` no lugar de `GENERATOR`) ficam no arquivo `allowed-diffs`, revisado a cada mudança.
- **Ida e volta**: extrair o metadata de um banco, rodar o script num banco vazio, extrair de novo e comparar os dois scripts; para dados, comparar o conteúdo das tabelas. Cobre tudo o que o IBExpert não gera e prova que a ordem de dependência faz o script rodar de ponta a ponta.
- Os testes rodam na suíte normal, sem IBExpert instalado, e exigem o Firebird 5 local (`fbdebug-test.properties`). Regenerar as referências é um passo manual, documentado em `docs/`.

## Alternativas descartadas

- **Só ida e volta**: não diz se a saída é equivalente à do IBExpert, e o usuário usa isso como régua.
- **Comparação textual com o IBExpert**: falha por formatação e por escolhas legítimas (`COMMENT ON`).
- **Referência gerada a partir de banco de cliente**: dados e nomes que não podem ser versionados.

## Consequências

- Alguém precisa gerar as referências no IBExpert (num servidor 2.5, se ele não abrir o banco 5) toda vez que o banco de teste mudar.
- Cada diferença aceita é uma decisão explícita e registrada.
