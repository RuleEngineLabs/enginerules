package br.com.lucaslima.enginerules.adapter.web.admin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.lucaslima.enginerules.application.shared.InvalidRequestException;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.PolicyDocument;

final class RequestBodies {

    private RequestBodies() {
    }

    static String requiredText(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidRequestException("'" + field + "' is required and must be a string");
        }
        return text;
    }

    static PolicyDefinition definition(Map<String, Object> body) {
        if (!body.containsKey("definition")) {
            throw new InvalidRequestException("'definition' is required");
        }
        return PolicyDocument.read(body.get("definition"));
    }

    static Set<String> textSet(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new InvalidRequestException("'" + field + "' must be an array of strings");
        }
        Set<String> result = new LinkedHashSet<>();
        items.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }
}
