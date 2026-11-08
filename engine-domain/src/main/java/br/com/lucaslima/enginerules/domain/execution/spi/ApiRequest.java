package br.com.lucaslima.enginerules.domain.execution.spi;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import br.com.lucaslima.enginerules.domain.definition.CanonicalForm;

public record ApiRequest(
        Protocol protocol,
        String connection,
        String method,
        String pathTemplate,
        Map<String, String> pathParams,
        Map<String, String> query,
        Object body,
        Duration timeout) {

    public ApiRequest {
        Objects.requireNonNull(protocol, "protocol");
        pathParams = pathParams == null ? Map.of() : Map.copyOf(pathParams);
        query = query == null ? Map.of() : Map.copyOf(query);
    }

    public static ApiRequest rest(String connection, String method, String pathTemplate, Map<String, String> pathParams,
                                  Map<String, String> query, Object body, Duration timeout) {
        return new ApiRequest(Protocol.REST, connection, method, pathTemplate, pathParams, query, body, timeout);
    }

    public static ApiRequest grpc(String connection, String service, String rpc, Object message, Duration timeout) {
        return new ApiRequest(Protocol.GRPC, connection, rpc, service, Map.of(), Map.of(), message, timeout);
    }

    public String grpcService() {
        return pathTemplate;
    }

    public String grpcRpc() {
        return method;
    }

    public String signature() {
        return String.join(" ", protocol.contractName(), connection, method, pathTemplate,
                new TreeMap<>(pathParams).toString(), new TreeMap<>(query).toString(), CanonicalForm.of(body));
    }
}
