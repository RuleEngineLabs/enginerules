package br.com.lucaslima.enginerules.domain.definition;

import java.util.List;

public record RetryDefinition(Integer maxAttempts, List<Long> backoffMs) {

    public RetryDefinition {
        backoffMs = backoffMs == null ? List.of() : List.copyOf(backoffMs);
    }
}
