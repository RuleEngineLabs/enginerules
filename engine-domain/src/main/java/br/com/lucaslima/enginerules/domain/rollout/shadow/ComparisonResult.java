package br.com.lucaslima.enginerules.domain.rollout.shadow;

public enum ComparisonResult {
    EQUAL,
    DIVERGENT_OUTCOME,
    DIVERGENT_DATA,
    SHADOW_ERROR_ONLY,
    REAL_ERROR_ONLY,
    NOT_COMPARABLE,
    DROPPED
}
