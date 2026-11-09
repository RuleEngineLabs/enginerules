package br.com.lucaslima.enginerules.application.execution;

import java.time.Duration;
import java.util.Objects;

public record ExecutionLimits(Duration timeout, int stepBudget) {

    public ExecutionLimits {
        Objects.requireNonNull(timeout, "timeout");
    }
}
