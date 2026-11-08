# ADR 0007 — Estratégia de testes e gates de cobertura

Status: aceito · 2026-11-07

## Decisão

- **Unitários por módulo**, sem Spring: domínio e aplicação com fakes em memória; adaptadores com o recurso real mais barato (diretório temporário, `HttpServer` do JDK, servidor gRPC in-process).
- **Gate JaCoCo de linha ≥ 98%** em `engine-domain`, `engine-application` e em todo `adapter-*`, ligado ao `check`. Os apps (composição) e os módulos de teste ficam fora: são cobertos pelos integrados.
- **Separação de passos (como no Go):** `./gradlew build` roda só compilação com `-Werror`, unitários, ArchUnit e o gate; os integrados são a task `:integration-tests:integrationTest`, segundo job de `develop` e `release/**`, contra o LocalStack.
- **Integrados por feature** (`integration-tests`): sobem `AdminApplication` e `EngineApplication` reais em portas aleatórias, com store temporário compartilhado, um provider REST falso (`HttpServer`) e um servidor gRPC falso (descriptor programático). Os testes falam só HTTP, como um consumidor. Propriedades vão como argumentos de linha de comando para vencer o `application.yaml` dos dois apps no mesmo classpath.
- Cada feature tem pelo menos 5 cenários e cobre o caminho feliz, as recusas de regra de negócio, a autorização e a validação de entrada. Nomes de política únicos por teste isolam os cenários num ambiente compartilhado.
- Assíncrono (sombra, métricas, avaliação do canary) é verificado com espera ativa limitada (`Eventually`, 15 s), nunca com `sleep` fixo; o agendador do canary roda a cada 1 h nos testes e a avaliação é disparada por `POST /canary-evaluations`.

| Feature | Classe | Cenários |
|---|---|---|
| Criação de política | `PoliciesIT` | 8 |
| Publicação de versão | `VersionPublishingIT` | 8 |
| STABLE (promoção/rollback) | `StableVersionIT` | 5 |
| Remoção de versão | `VersionRemovalIT` | 6 |
| Consultas do catálogo | `PolicyQueriesIT` | 5 |
| Auditoria | `AuditsIT` | 6 |
| Execução (REST, retry, timeout, cache, escrita) | `ExecutionsIT` | 10 |
| Preview | `PreviewsIT` | 6 |
| Canary | `CanariesIT` | 7 |
| Sombra | `ShadowsIT` | 7 |
| gRPC | `GrpcIT` | 7 |
| Store AWS (só com LocalStack) | `AwsStorageIT` | 6 |

Com `ENGINERULES_IT_AWS_ENDPOINT` definido, todo o conjunto roda com o store em DynamoDB + S3 (prefixo de tabela e bucket únicos por execução); sem ele, com o store em arquivo e `AwsStorageIT` é pulado.

## Fora dos integrados

Promoção automática pelo prazo do canary exige esperar `durationMinutes` (mínimo 1 min); fica nos unitários do `CanaryEvaluator`/`CanaryEvaluationService` com relógio fixo.
