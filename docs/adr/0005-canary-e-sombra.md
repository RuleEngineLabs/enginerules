# ADR 0005 — Canary e modo sombra como subdomínio `rollout`

Status: aceito · 2026-11-07

## Decisão

- `domain.rollout` é um subdomínio supporting com dois aggregates pequenos e independentes, `Canary` e `Shadow`, que referenciam a política e as versões só por nome/número (sem chave estrangeira, como nas tabelas separadas do desenho).
- Regras no domínio: roteamento estável por hash do `consumerId` (`TrafficSelector`), ciclo de vida e orçamento de extensão do canary, prazo/pausa da sombra, `CanaryEvaluator` (comparativo, interrupção antecipada, inconclusivo) e `ShadowComparator` (caminhos ignorados, ausente = null).
- I/O virtual é domínio também: `IoRecording` decora o `IoBinding` da execução real e `VirtualIo` responde a sombra com replay e depois stub. O `PolicyExecutor` passou a aceitar um `IoBinding` por execução, sem conhecer sombra.
- Na aplicação, `TrafficRouter` e `ShadowDispatcher` ficam no fluxo de execução; os use cases de canary/sombra e o avaliador ficam em `application.rollout`. O catálogo só conhece `RolloutGuardPort` para recusar `update`/`delete`, o que evita ciclo entre os dois pacotes.
- Promoção pelo canary segue o G17: marca `CONCLUDING`, move a STABLE pelo aggregate `Policy` e só então conclui; se parar no meio, a próxima avaliação retoma.

## Atalhos

- Métricas locais em arquivo (JSONL), lidas pelo admin; trocar por Prometheus é um novo adaptador de `QueryMetricsPort`/`RecordMetricsPort`.
- Canary e sombra são gravados com last-write-wins (o admin é o único escritor); concorrência otimista fica para o adaptador DynamoDB.
- Uma falta de resposta virtual marca a requisição inteira como `NOT_COMPARABLE` (o desenho prevê marcar só o trecho).
