package br.com.lucaslima.enginerules.adapter.http;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import br.com.lucaslima.enginerules.adapter.json.Json;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.Protocol;

public final class HttpApiGateway implements ApiGateway {

    private final HttpClient client;
    private final Map<String, HttpConnection> connections;

    public HttpApiGateway(Map<String, HttpConnection> connections, Duration connectTimeout) {
        this.connections = Map.copyOf(connections);
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ApiResponse call(ApiRequest request) {
        if (request.protocol() != Protocol.REST) {
            return ApiResponse.failure("the HTTP gateway only serves REST calls");
        }
        HttpConnection connection = connections.get(request.connection());
        if (connection == null) {
            return ApiResponse.failure("unknown connection '" + request.connection() + "'");
        }
        try {
            HttpResponse<String> response = client.send(toHttpRequest(connection, request),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return ApiResponse.of(response.statusCode(), parseBody(response), firstHeaderValues(response));
        } catch (HttpTimeoutException timeout) {
            return ApiResponse.timedOut();
        } catch (IOException failure) {
            return ApiResponse.failure(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ApiResponse.failure("interrupted");
        }
    }

    private HttpRequest toHttpRequest(HttpConnection connection, ApiRequest request) {
        HttpRequest.BodyPublisher body = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(request.body()), StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(connection, request))
                .timeout(request.timeout())
                .method(request.method(), body)
                .header("Accept", "application/json");
        if (request.body() != null) {
            builder.header("Content-Type", "application/json");
        }
        connection.headers().forEach(builder::header);
        return builder.build();
    }

    private static URI uri(HttpConnection connection, ApiRequest request) {
        String path = request.pathTemplate();
        for (Map.Entry<String, String> parameter : request.pathParams().entrySet()) {
            path = path.replace("{" + parameter.getKey() + "}", encode(parameter.getValue()));
        }
        String base = connection.baseUrl().toString().replaceAll("/+$", "");
        String query = request.query().entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(base + path + (query.isEmpty() ? "" : "?" + query));
    }

    private static Object parseBody(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (contentType.contains("json")) {
            try {
                return Json.parse(body);
            } catch (IllegalArgumentException notJson) {
                return body;
            }
        }
        return body;
    }

    private static Map<String, String> firstHeaderValues(HttpResponse<String> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(), values.get(0));
            }
        });
        return headers;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
