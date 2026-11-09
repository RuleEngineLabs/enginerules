package br.com.lucaslima.enginerules.adapter.web.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;

public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static int statusOf(CatalogErrorCode code) {
        return switch (code) {
            case INVALID_POLICY_NAME, INVALID_VERSION, BASED_ON_REQUIRED -> 400;
            case NOT_OWNER, CONSUMER_NOT_ALLOWED -> 403;
            case POLICY_NOT_FOUND, VERSION_NOT_FOUND -> 404;
            case VERSION_REMOVED -> 410;
            case POLICY_ALREADY_EXISTS, BASED_ON_NOT_STABLE, PENDING_CANDIDATE_EXISTS, PENDING_CANDIDATE_NOT_EXECUTABLE,
                 VERSION_IS_STABLE, ALREADY_STABLE, CANARY_IN_PROGRESS, VERSION_IN_USE, CONCURRENT_MODIFICATION -> 409;
        };
    }

    public static int statusOf(RolloutErrorCode code) {
        return switch (code) {
            case INVALID_CANARY, INVALID_SHADOW -> 400;
            case CANARY_NOT_FOUND, SHADOW_NOT_FOUND -> 404;
            case CANARY_ACTIVE, CANARY_NOT_ACTIVE, CANARY_MAJOR_BLOCKED, CANDIDATE_NOT_PENDING, CANARY_NOT_INCONCLUSIVE,
                 EXTENSION_BUDGET_EXCEEDED, SHADOW_ACTIVE, SHADOW_NOT_LIVE -> 409;
        };
    }

    public static Map<String, Object> body(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }

    public static Map<String, Object> body(String error, String message, List<String> details) {
        Map<String, Object> body = body(error, message);
        body.put("details", details);
        return body;
    }
}
