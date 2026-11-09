package br.com.lucaslima.enginerules.domain.rollout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryStatus;

class CanaryTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    @Test
    void routesTheSameConsumerToTheSameSideWhileOpen() {
        Canary canary = canary(30);
        Random random = new Random(7);

        Route first = canary.route(Optional.of("checkout"), random);

        for (int attempt = 0; attempt < 50; attempt++) {
            assertEquals(first, canary.route(Optional.of("checkout"), random));
        }
    }

    @Test
    void splitsTrafficCloseToTheConfiguredWeight() {
        Canary canary = canary(20);
        Random random = new Random(11);
        int toCandidate = 0;
        for (int consumer = 0; consumer < 10_000; consumer++) {
            if (canary.route(Optional.of("consumer-" + consumer), random) == Route.CANARY) {
                toCandidate++;
            }
        }

        assertTrue(toCandidate > 1_700 && toCandidate < 2_300, "candidate share was " + toCandidate);
    }

    @Test
    void rejectsWeightsOutsideOneToNinetyNine() {
        RolloutException failure = assertThrows(RolloutException.class, () -> canary(100));

        assertEquals(RolloutErrorCode.INVALID_CANARY, failure.code());
    }

    @Test
    void extensionsAreOnlyForInconclusiveCanariesAndRespectTheBudget() {
        Canary canary = canary(10);
        assertEquals(RolloutErrorCode.CANARY_NOT_INCONCLUSIVE,
                assertThrows(RolloutException.class, () -> canary.extend(Duration.ofHours(1))).code());

        canary.markInconclusive();
        canary.extend(Duration.ofHours(20));
        canary.markInconclusive();

        assertEquals(RolloutErrorCode.EXTENSION_BUDGET_EXCEEDED,
                assertThrows(RolloutException.class, () -> canary.extend(Duration.ofHours(5))).code());
        assertEquals(NOW.plus(Duration.ofHours(44)), canary.decisionDeadline());
        assertEquals(NOW.plus(Duration.ofHours(48)), canary.absoluteDeadline());
    }

    @Test
    void promotionGoesThroughConcluding() {
        Canary canary = canary(10);

        canary.beginConclusion();
        canary.completePromotion(NOW);

        assertEquals(CanaryStatus.PROMOTED, canary.status());
        assertEquals(Route.STABLE, canary.route(Optional.of("x"), new Random()));
    }

    private static Canary canary(int weight) {
        return Canary.start("c-1", PolicyName.of("CreditLimit"), SemanticVersion.parse("1.1.0"), SemanticVersion.FIRST, weight,
                null, CanaryCriteria.GLOBAL_DEFAULT, Optional.empty(), "ana", NOW);
    }
}
