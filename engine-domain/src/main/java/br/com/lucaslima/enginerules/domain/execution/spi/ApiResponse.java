package br.com.lucaslima.enginerules.domain.execution.spi;

import java.util.LinkedHashMap;
import java.util.Map;

public record ApiResponse(Integer status, Object body, Map<String, String> headers, String error, boolean timeout) {

    public ApiResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static ApiResponse of(int status, Object body, Map<String, String> headers) {
        return new ApiResponse(status, body, headers, null, false);
    }

    public static ApiResponse failure(String error) {
        return new ApiResponse(null, null, Map.of(), error, false);
    }

    public static ApiResponse timedOut() {
        return new ApiResponse(null, null, Map.of(), null, true);
    }

    public Map<String, Object> asContextValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status);
        value.put("body", body);
        value.put("headers", headers);
        value.put("error", error);
        value.put("timeout", timeout);
        return value;
    }
}
