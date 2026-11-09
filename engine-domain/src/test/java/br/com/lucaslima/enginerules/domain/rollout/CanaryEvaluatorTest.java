package br.com.lucaslima.enginerules.domain.rollout;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryEvaluator;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryVerdict;
import br.com.lucaslima.enginerules.domain.rollout.canary.VersionStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;

class CanaryEvaluatorTest {

    private static final Instant STARTED = Instant.parse("2026-11-07T12:00:00Z");
    private static final Instant AFTER_DEADLINE = STARTED.plus(Duration.ofHours(1));
    private static final VersionStats HEALTHY = new VersionStats(2_000, 10, 1_000, 2_000);

    private final CanaryEvaluator evaluator = new CanaryEvaluator();
    private final Canary canary = Canary.start("c-1", PolicyName.of("CreditLimit"), SemanticVersion.parse("1.1.0"),
            SemanticVersion.FIRST, 10, Duration.ofMinutes(30), CanaryCriteria.GLOBAL_DEFAULT, Optional.empty(), "ana", STARTED);

    @Test
    void keepsObservingUntilTheDecisionDeadline() {
        assertInstanceOf(CanaryVerdict.KeepObserving.class,
                evaluator.evaluate(canary, HEALTHY, HEALTHY, Optional.empty(), STARTED.plusSeconds(60)));
    }

    @Test
    void interruptsEarlyOnSustainedCandidateErrors() {
        VersionStats failing = new VersionStats(200, 40, 1_000, 2_000);

        assertInstanceOf(CanaryVerdict.Abort.class,
                evaluator.evaluate(canary, HEALTHY, failing, Optional.empty(), STARTED.plusSeconds(60)));
    }

    @Test
    void promotesWhenEveryCriterionPasses() {
        VersionStats candidate = new VersionStats(1_500, 9, 1_100, 2_200);

        assertInstanceOf(CanaryVerdict.Promote.class,
                evaluator.evaluate(canary, HEALTHY, candidate, Optional.empty(), AFTER_DEADLINE));
    }

    @Test
    void abortsWhenLatencyExceedsTheRatio() {
        VersionStats slow = new VersionStats(1_500, 5, 1_500, 2_100);

        CanaryVerdict.Abort abort = assertInstanceOf(CanaryVerdict.Abort.class,
                evaluator.evaluate(canary, HEALTHY, slow, Optional.empty(), AFTER_DEADLINE));
        assertTrue(abort.reasons().get(0).startsWith("p95"));
    }

    @Test
    void isInconclusiveWithoutEnoughSamplesAndAbortsWhenTheBudgetIsGone() {
        VersionStats few = new VersionStats(100, 0, 1_000, 2_000);

        assertInstanceOf(CanaryVerdict.Inconclusive.class,
                evaluator.evaluate(canary, HEALTHY, few, Optional.empty(), AFTER_DEADLINE));
        assertInstanceOf(CanaryVerdict.Abort.class,
                evaluator.evaluate(canary, HEALTHY, few, Optional.empty(), canary.absoluteDeadline()));
    }

    @Test
    void usesShadowIncompatibilityWhenTheReferencedShadowIsLive() {
        ShadowStats incompatible = new ShadowStats(Map.of(ComparisonResult.EQUAL, 90L, ComparisonResult.SHADOW_ERROR_ONLY, 10L), 1, 1);

        assertInstanceOf(CanaryVerdict.Abort.class,
                evaluator.evaluate(canary, HEALTHY, HEALTHY, Optional.of(incompatible), AFTER_DEADLINE));
    }
}
