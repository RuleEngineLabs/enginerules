# ADR 0002 — Subdomínios, bounded contexts e aggregates

Status: aceito · 2026-11-07

## Subdomínios (inferência a confirmar com o time)

| Subdomínio | Tipo | Padrão |
|---|---|---|
| Execução de políticas (`domain.execution`) | Core | Domain model: compilador + máquina de estados + guardrails |
| Catálogo e ciclo de vida de versões (`domain.catalog`) | Core | Domain model com aggregate e eventos |
| Contrato da política (`domain.definition`) | Shared kernel / Published Language | Value objects imutáveis + forma canônica |
| Canary, sombra, toggles, auditoria | Supporting | A implementar; transaction script/serviços simples |
| Autenticação, armazenamento, métricas | Generic | Cognito, DynamoDB, AMP — via adaptadores |

## Aggregates

- `Policy` (raiz) guarda o "item META" do desenho: dono, aprovadores, allowlist, marcador STABLE, candidata pendente, geração da STABLE e o registro de todas as versões emitidas (número, hash, autor, remoção). É ele que garante os invariantes: exatamente uma STABLE viva, uma candidata por vez, `basedOn == STABLE`, números nunca reutilizados, idempotência por hash, promoção vs. rollback.
- `PolicyVersion` é um aggregate separado e imutável (conteúdo + hash). Referenciado pela raiz só por número.
- Mudanças no aggregate geram `PolicyEvent`s, que a aplicação converte em registros de auditoria.

## Atalho consciente

Publicar uma versão grava dois aggregates (`Policy` e o novo `PolicyVersion`) numa chamada de porta (`SavePolicyPort.savePolicy(policy, newVersion)`), violando "um aggregate por transação". Motivo: o próprio desenho prevê a escrita condicional do META junto com o item da versão numa transação do DynamoDB. O conteúdo da versão é imutável e gravado antes do META, então uma falha no meio deixa só um arquivo órfão inofensivo.
