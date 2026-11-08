package br.com.lucaslima.enginerules.domain.rollout.canary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;

public final class CanaryEvaluator {

    public static final double EARLY_ABORT_ERROR_RATE = 0.05;
    public static final long EARLY_ABORT_MIN_SAMPLES = 100;

    public CanaryVerdict evaluate(Canary canary, VersionStats baseline, VersionStats candidate,
                                  Optional<ShadowStats> shadow, Instant now) {
        if (sustainedErrors(baseline, candidate)) {
            return new CanaryVerdict.Abort(List.of(String.format(
                    "early interruption: candidate error rate %.2f%% over %d samples", candidate.errorRate() * 100,
                    candidate.samples())));
        }
        if (now.isBefore(canary.decisionDeadline())) {
            return new CanaryVerdict.KeepObserving();
        }
        CanaryCriteria criteria = canary.criteria();
        if (baseline.samples() < criteria.minSamples() || candidate.samples() < criteria.minSamples()) {
            if (!now.isBefore(canary.absoluteDeadline())) {
                return new CanaryVerdict.Abort(List.of("extension budget exhausted without " + criteria.minSamples()
                        + " samples per version"));
            }
            return new CanaryVerdict.Inconclusive("stable has " + baseline.samples() + " and candidate "
                    + candidate.samples() + " samples; " + criteria.minSamples() + " required");
        }
        List<String> failures = failedCriteria(criteria, baseline, candidate, shadow);
        return failures.isEmpty() ? new CanaryVerdict.Promote() : new CanaryVerdict.Abort(failures);
    }

    private static boolean sustainedErrors(VersionStats baseline, VersionStats candidate) {
        return candidate.samples() >= EARLY_ABORT_MIN_SAMPLES
                && candidate.errorRate() >= EARLY_ABORT_ERROR_RATE
                && candidate.errorRate() > baseline.errorRate();
    }

    private static List<String> failedCriteria(CanaryCriteria criteria, VersionStats baseline, VersionStats candidate,
                                               Optional<ShadowStats> shadow) {
        List<String> failures = new ArrayList<>();
        double errorDeltaPoints = (candidate.errorRate() - baseline.errorRate()) * 100;
        if (errorDeltaPoints > criteria.errorMarginPercentagePoints()) {
            failures.add(String.format("error rate +%.2fpp over stable exceeds %.2fpp", errorDeltaPoints,
                    criteria.errorMarginPercentagePoints()));
        }
        if (exceedsRatio(candidate.p95Micros(), baseline.p95Micros(), criteria.maxLatencyRatio())) {
            failures.add("p95 " + candidate.p95Micros() + "us exceeds " + criteria.maxLatencyRatio() + "x stable "
                    + baseline.p95Micros() + "us");
        }
        if (exceedsRatio(candidate.p99Micros(), baseline.p99Micros(), criteria.maxLatencyRatio())) {
            failures.add("p99 " + candidate.p99Micros() + "us exceeds " + criteria.maxLatencyRatio() + "x stable "
                    + baseline.p99Micros() + "us");
        }
        shadow.filter(stats -> stats.comparable() > 0)
                .filter(stats -> stats.inputIncompatibilityRate() > criteria.maxInputIncompatibilityRate())
                .ifPresent(stats -> failures.add(String.format("shadow input incompatibility %.2f%% exceeds %.2f%%",
                        stats.inputIncompatibilityRate() * 100, criteria.maxInputIncompatibilityRate() * 100)));
        return failures;
    }

    private static boolean exceedsRatio(long candidate, long baseline, double maxRatio) {
        return baseline > 0 && candidate > baseline * maxRatio;
    }
}
