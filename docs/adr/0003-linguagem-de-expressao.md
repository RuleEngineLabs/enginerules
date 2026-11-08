# ADR 0003 — Linguagem de expressão atrás de uma porta, com implementação própria

Status: aceito · 2026-11-07

## Contexto

No Go a escolha foi `expr-lang/expr` (compila antes, sem reflection). Em Java as opções maduras (MVEL, SpEL, JEXL) são justamente as que dependem de reflection e motivaram a saída do motor MVEL; CEL-Java é uma alternativa forte, mas muda a sintaxe e adiciona dependências pesadas.

## Decisão

- O domínio define `ExpressionLanguage`/`CompiledExpression` (SPI) e não conhece nenhuma implementação.
- `adapter-expression-builtin` compila cada expressão para uma árvore de nós imutáveis (lexer + parser de precedência) uma única vez, no compile da política. Avaliar é percorrer a árvore: sem reflection, sem geração de bytecode, compatível com GraalVM native.
- A sintaxe segue o subconjunto do `expr` usado nas políticas (`a.b`, `in`, `?:`, `all/any/filter/map/count` com `#`).
- A implementação expõe as referências de campo (`raiz.campo`) para o guard de campo obrigatório do compilador.

## Consequências

- Trocar por CEL ou outra engine é escrever um novo adaptador da mesma SPI.
- Funções novas exigem código no adaptador (lista fechada e auditável — desejável para um motor de regras).
- Paralelismo continua no host (virtual threads no `parallel`), nunca na linguagem.
