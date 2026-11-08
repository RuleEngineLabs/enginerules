# ADR 0001 — Hexagonal com um módulo Gradle por camada e por adaptador

Status: aceito · 2026-11-07

## Contexto

O motor precisa trocar detalhes (linguagem de expressão, DynamoDB vs. arquivo, HTTP vs. gRPC, Spring vs. outro runtime) sem tocar nas regras de execução e de ciclo de vida das políticas. Em projeto de módulo único, a regra da dependência depende de disciplina e erode.

## Decisão

- `engine-domain` (Java puro) → `engine-application` (use cases e portas) → adaptadores → apps.
- Cada adaptador é um módulo próprio que só enxerga o núcleo; adaptadores não dependem entre si (exceção: `adapter-json-codec`, utilitário sem regra).
- Os `apps` são o único lugar que monta o grafo de objetos (`@Configuration` + `@Bean`). Nenhuma classe do núcleo é anotada com Spring.
- `architecture-tests` falha o build se a regra for violada (ArchUnit).

## Consequências

- O compilador impede o domínio de importar Spring/HTTP/JSON: as dependências simplesmente não estão no classpath.
- Dois executáveis (`app-engine` e `app-admin`) reaproveitam o mesmo núcleo com conjuntos diferentes de adaptadores, como `cmd/engine` e `cmd/admin` no Go.
- Mais arquivos de build e um pouco mais de wiring manual — aceito.
