# ADR 0008 — DynamoDB e S3 como store de produção

Status: aceito · 2026-11-07

## Contexto

`infra-entorno-engine.md` (seção 7) e as decisões de 2026-09-28 definem o DynamoDB como store padrão, o S3 como origem de contingência da STABLE e o LocalStack para dev/local. O store em arquivo (ADR 0004) era só para desenvolvimento.

## Decisão

- Novo `adapter-persistence-aws` implementa as mesmas portas do store em arquivo; o núcleo não muda. A escolha é por configuração (`enginerules.store.type: filesystem | aws`).
- O mapeamento snapshot ↔ documento saiu do adaptador de arquivo para `adapter-persistence-documents`, usado pelos dois. Os três ficam no mesmo slice `adapter.persistence` do ArchUnit.
- Tabelas (prefixo configurável):

| Tabela | Chave | Conteúdo |
|---|---|---|
| `policies` | `policyName` + `sk` | item `META` (`ptr`, `pendingCandidate`, `owner`, `revision`, documento) e um item imutável `v#X.Y.Z` por versão (`hash`, documento) |
| `policy-index` | `IDX#ALL` + nome | `owner`, `pendingCandidate` (bool), `ptr`; alimenta a listagem paginada |
| `audit` | `policyName` + `sk` | append-only; `sk` = micros desde a época (20 dígitos) + sequência + UUID, para a ordem cronológica não depender da formatação do instante |
| `canaries`, `shadows` | `policyName` + id | documento; `gsiActiveStatus = ACTIVE` só enquanto aberto/vivo, alimentando o GSI esparso `active-by-status` |

- Publicar grava versão, `META` e índice numa `TransactWriteItems`: a versão só entra se não existir ou tiver o mesmo hash; o `META` exige a `revision` esperada (ou não existir, na criação). Cancelamento vira `concurrent_modification`, como no store em arquivo.
- Snapshot da STABLE em S3, um objeto por política (`stable/<nome>.json`, decisão de 2026-09-28): o admin regrava a cada escrita aceita. Falha no S3 é registrada e não desfaz a escrita no DynamoDB; a próxima escrita corrige.
- No engine, `SnapshotFallbackCatalog` lê do DynamoDB e, se o SDK falhar, serve a STABLE do snapshot e entra em modo de origem alternativa (sai sozinho quando o DynamoDB responde). Versões que não são a STABLE não existem nesse modo.
- Só o admin cria tabelas e bucket (`create-resources`, para LocalStack); em ambiente real quem cria é o Terraform, e o engine só lê.

## Atalhos conscientes

- Auditoria ainda em segunda etapa (`append` depois de `savePolicy`), sem entrar na transação: a porta de auditoria é separada da de política. Unificar exige uma porta de "commit" no use case.
- `PolicyIndex` tem uma lista só (`IDX#ALL`); filtros por dono e candidata são aplicados em memória. As listas materializadas por filtro do desenho entram quando o volume pedir.
- Sem manifesto por tabela nem lease de líder: o refresh continua por TTL e a avaliação de canary roda em todas as tasks do admin.
- Exportação periódica do snapshot (além da exportação a cada escrita) fica para o lease de líder.
- Métricas continuam em arquivo; o adaptador Prometheus/AMP é o próximo passo.
