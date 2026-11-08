package br.com.lucaslima.enginerules.domain.rollout.canary;

import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;

public record CanaryCriteria(
        double errorMarginPercentagePoints,
        double maxLatencyRatio,
        long minSamples,
        double maxInputIncompatibilityRate) {

    public static final CanaryCriteria GLOBAL_DEFAULT = new CanaryCriteria(0.5, 1.2, 1_000, 0.0);

    public CanaryCriteria {
        if (errorMarginPercentagePoints < 0 || maxLatencyRatio < 1 || minSamples < 1
                || maxInputIncompatibilityRate < 0 || maxInputIncompatibilityRate > 1) {
            throw new RolloutException(RolloutErrorCode.INVALID_CANARY,
                    "criteria need errorMargin >= 0, latencyRatio >= 1, minSamples >= 1 and incompatibility in [0, 1]");
        }
    }
}
