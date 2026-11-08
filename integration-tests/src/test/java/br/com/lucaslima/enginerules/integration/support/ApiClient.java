package br.com.lucaslima.enginerules.integration.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import br.com.lucaslima.enginerules.adapter.json.Json;

public final class ApiClient {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final URI baseUri;
    private final Map<String, String> defaultHeaders;

    public ApiClient(URI baseUri) {
        this(baseUri, Map.of());
    }

    private ApiClient(URI baseUri, Map<String, String> defaultHeaders) {
        this.baseUri = baseUri;
        this.defaultHeaders = Map.copyOf(defaultHeaders);
    }

    public ApiClient as(String actor, String groups) {
        Map<String, String> headers = new LinkedHashMap<>(defaultHeaders);
        headers.put("X-Actor-Id", actor);
        headers.put("X-Actor-Groups", groups);
        return new ApiClient(baseUri, headers);
    }

    public ApiClient anonymous() {
        return new ApiClient(baseUri);
    }

    public ApiResponse get(String path, String... headers) {
        return send("GET", path, null, headers);
    }

    public ApiResponse post(String path, Object body, String... headers) {
        return send("POST", path, body, headers);
    }

    public ApiResponse put(String path, Object body, String... headers) {
        return send("PUT", path, body, headers);
    }

    public ApiResponse patch(String path, Object body, String... headers) {
        return send("PATCH", path, body, headers);
    }

    public ApiResponse delete(String path, String... headers) {
        return send("DELETE", path, null, headers);
    }

    private ApiResponse send(String method, String path, Object body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        defaultHeaders.forEach(request::header);
        for (int index = 0; index + 1 < headers.length; index += 2) {
            request.header(headers[index], headers[index + 1]);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            Object parsed = text == null || text.isBlank() ? null : parse(text);
            return new ApiResponse(response.statusCode(), response.headers().map(), parsed);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Object parse(String text) {
        try {
            return Json.parse(text);
        } catch (IllegalArgumentException notJson) {
            return text;
        }
    }
}
