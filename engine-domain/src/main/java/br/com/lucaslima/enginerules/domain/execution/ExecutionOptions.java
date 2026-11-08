package br.com.lucaslima.enginerules.domain.execution;

import java.time.Duration;
import java.util.Objects;

public record ExecutionOptions(ExecutionMode mode, boolean traceEnabled, Duration timeout, int stepBudget) {

    public ExecutionOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (stepBudget < 1) {
            throw new IllegalArgumentException("stepBudget must be positive");
        }
    }

    public static ExecutionOptions live(boolean traceEnabled, Duration timeout, int stepBudget) {
        return new ExecutionOptions(ExecutionMode.LIVE, traceEnabled, timeout, stepBudget);
    }

    public static ExecutionOptions preview(Duration timeout, int stepBudget) {
        return new ExecutionOptions(ExecutionMode.PREVIEW, true, timeout, stepBudget);
    }
}
