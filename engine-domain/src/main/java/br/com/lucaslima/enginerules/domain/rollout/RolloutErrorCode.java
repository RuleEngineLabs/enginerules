package br.com.lucaslima.enginerules.domain.rollout;

public enum RolloutErrorCode {
    INVALID_CANARY("invalid_canary"),
    CANARY_ACTIVE("canary_active"),
    CANARY_NOT_FOUND("canary_not_found"),
    CANARY_NOT_ACTIVE("canary_not_active"),
    CANARY_MAJOR_BLOCKED("canary_major_blocked"),
    CANDIDATE_NOT_PENDING("candidate_not_pending"),
    CANARY_NOT_INCONCLUSIVE("canary_not_inconclusive"),
    EXTENSION_BUDGET_EXCEEDED("extension_budget_exceeded"),
    INVALID_SHADOW("invalid_shadow"),
    SHADOW_ACTIVE("shadow_active"),
    SHADOW_NOT_FOUND("shadow_not_found"),
    SHADOW_NOT_LIVE("shadow_not_live");

    private final String code;

    RolloutErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
