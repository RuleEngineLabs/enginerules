package br.com.lucaslima.enginerules.adapter.http;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

public record HttpConnection(URI baseUrl, Map<String, String> headers) {

    public HttpConnection {
        Objects.requireNonNull(baseUrl, "baseUrl");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
