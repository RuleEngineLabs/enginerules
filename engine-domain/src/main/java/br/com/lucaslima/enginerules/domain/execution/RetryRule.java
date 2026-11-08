package br.com.lucaslima.enginerules.domain.execution;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;

public record RetryRule(int maxAttempts, List<Long> backoffMs) {

    public static final int MAX_ATTEMPTS_CEILING = 3;
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 500, 502, 503, 504);
    public static final RetryRule NO_RETRY = new RetryRule(1, List.of());

    public RetryRule {
        backoffMs = List.copyOf(backoffMs);
    }

    public boolean shouldRetry(ApiResponse response, int attemptsMade) {
        return attemptsMade < maxAttempts && isRetryable(response);
    }

    public Duration backoffAfter(int attemptsMade) {
        if (backoffMs.isEmpty()) {
            return Duration.ZERO;
        }
        int index = Math.min(attemptsMade - 1, backoffMs.size() - 1);
        return Duration.ofMillis(backoffMs.get(index));
    }

    private static boolean isRetryable(ApiResponse response) {
        return response.timeout() || response.error() != null
                || (response.status() != null && RETRYABLE_STATUSES.contains(response.status()));
    }
}
