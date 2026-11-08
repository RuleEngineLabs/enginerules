package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.util.Map;
import java.util.Objects;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;

public record VirtualRoute(String connection, String method, String pathTemplate, int status, Object body) {

    public VirtualRoute {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(pathTemplate, "pathTemplate");
        method = method == null ? "GET" : method;
        if (status < 100 || status > 599) {
            throw new RolloutException(RolloutErrorCode.INVALID_SHADOW, "virtual route status must be a valid HTTP status");
        }
    }

    public boolean matches(ApiRequest request) {
        return connection.equals(request.connection()) && method.equalsIgnoreCase(request.method())
                && (pathTemplate.equals("*") || pathTemplate.equals(request.pathTemplate()));
    }

    public ApiResponse response() {
        return ApiResponse.of(status, body, Map.of());
    }
}
