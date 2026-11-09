package br.com.lucaslima.enginerules.domain.execution;

import java.util.EnumMap;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.Protocol;

public final class ProtocolRoutingGateway implements ApiGateway {

    private final Map<Protocol, ApiGateway> gateways;

    public ProtocolRoutingGateway(Map<Protocol, ApiGateway> gateways) {
        this.gateways = new EnumMap<>(gateways);
    }

    @Override
    public ApiResponse call(ApiRequest request) {
        ApiGateway gateway = gateways.get(request.protocol());
        if (gateway == null) {
            return ApiResponse.failure("no gateway for protocol " + request.protocol().contractName());
        }
        return gateway.call(request);
    }
}
