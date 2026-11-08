# ADR 0006 — gRPC como protocolo do `apiCall`

Status: aceito · 2026-11-07

## Contexto

Parte dos providers internos expõe só gRPC. O `apiCall` era REST puro e o compilador validava apenas campos HTTP.

## Decisão

- gRPC é um **protocolo do mesmo kind** (`protocol: "grpc"`), não um kind novo: transitions, retry, timeout, `contextKey` e o envelope `result {status, body, headers, error, timeout}` continuam iguais, então a política troca de transporte sem mudar o resto do grafo.
- O domínio só conhece `Protocol` e `ApiRequest.grpc(...)`; `ProtocolRoutingGateway` escolhe o gateway pelo protocolo. Nada de `io.grpc`/`protobuf` fora de `adapter.grpc` e dos apps (regra ArchUnit).
- Sem código gerado: o adaptador carrega um `FileDescriptorSet` por connection e usa `DynamicMessage` + `JsonFormat` (nomes do `.proto`). Publicar um `.proto` novo é trocar um arquivo de configuração.
- Contrato verificado no compile por uma porta do domínio (`ServiceContracts.verifyGrpcCall`): serviço, rpc, campos da mensagem; streaming é recusado. Admin e engine usam a mesma implementação, então o erro aparece no `publish` e não na primeira execução.
- Escrita por padrão: sem `readOnly: true` a chamada é escrita (bloqueada no preview, sem cache de resultado). É o lado seguro, já que gRPC não tem verbo que diga se é leitura.
- Status gRPC mapeado para o equivalente HTTP (tabela do gRPC-gateway), `DEADLINE_EXCEEDED` vira timeout, para as mesmas transitions (`result.status >= 500`) servirem aos dois protocolos.

## Consequências

- Valores padrão do proto3 não aparecem no `body` (comportamento do `JsonFormat`); políticas devem tratar ausência como valor padrão.
- Fora do escopo: streaming, TLS mútuo e credenciais por chamada; entram como opções da connection.
