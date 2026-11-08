package br.com.lucaslima.enginerules.integration.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ApiResponse(int status, Map<String, List<String>> headers, Object body) {

    public Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    public Map<String, Object> json() {
        if (body instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new AssertionError("expected a JSON object but got " + body + " (status " + status + ")");
    }

    public List<Object> list() {
        if (body instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> typed = (List<Object>) list;
            return typed;
        }
        throw new AssertionError("expected a JSON array but got " + body + " (status " + status + ")");
    }

    public Object at(String path) {
        Object current = body;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && segment.chars().allMatch(Character::isDigit)) {
                int index = Integer.parseInt(segment);
                current = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    public String text(String path) {
        Object value = at(path);
        return value == null ? null : value.toString();
    }

    public long number(String path) {
        Object value = at(path);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("expected a number at " + path + " but got " + value + " in " + body);
    }

    public double decimal(String path) {
        Object value = at(path);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new AssertionError("expected a number at " + path + " but got " + value + " in " + body);
    }

    public String error() {
        return text("error");
    }

    @Override
    public String toString() {
        return status + " " + body;
    }
}
