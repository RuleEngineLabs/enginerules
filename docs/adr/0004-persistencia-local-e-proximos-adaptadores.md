# ADR 0004 — Persistência local em arquivo como primeiro adaptador

Status: aceito · 2026-11-07

## Decisão

- `adapter-persistence-filesystem` implementa `LoadPolicyPort`, `LoadPolicyVersionPort`, `SavePolicyPort`, `ListPoliciesPort`, `AppendAuditPort` e `ListAuditsPort` sobre um diretório (`ENGINERULES_STORE_DIRECTORY`).
- Concorrência otimista por `revision` no META; lock de arquivo para o único escritor (admin); escrita atômica por arquivo temporário + move.
- Engine e admin compartilham o diretório: o engine lê com cache L1 e refresh por TTL (`catalog-refresh-interval`, padrão 30 s), com a regra de geração do G1 (gravação atrasada nunca sobrescreve uma STABLE mais nova).

## Atalhos e próximos passos

- Política e auditoria são gravadas em duas etapas (sem outbox). O adaptador DynamoDB fará as duas na mesma transação.
- A listagem varre o diretório; a tabela `PolicyIndex` substitui isso no adaptador DynamoDB, sem mudar a porta.
- DynamoDB + S3 entraram em `adapter-persistence-aws` (ADR 0008). Próximos adaptadores previstos: `adapter-auth-cognito`, `adapter-metrics-prometheus`, `adapter-db-query` (fase 3).
