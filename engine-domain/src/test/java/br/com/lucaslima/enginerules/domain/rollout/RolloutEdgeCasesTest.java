package br.com.lucaslima.enginerules.domain.rollout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.execution.ExecutionErrorCode;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;
import br.com.lucaslima.enginerules.domain.execution.IoBinding;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryEvaluator;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryStatus;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryVerdict;
import br.com.lucaslima.enginerules.domain.rollout.canary.VersionStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.IoRecording;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowComparator;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualIo;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualRoute;

class RolloutEdgeCasesTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final PolicyName POLICY = PolicyName.of("CreditLimit");
    private static final SemanticVersion CANDIDATE = SemanticVersion.parse("1.1.0");

    @Test
    void canaryValidatesItsSetup() {
        assertEquals(RolloutErrorCode.INVALID_CANARY, assertThrows(RolloutException.class, () -> canary(Duration.ofHours(30))).code());
        assertEquals(RolloutErrorCode.INVALID_CANARY, assertThrows(RolloutException.class, () -> canary(Duration.ZERO)).code());
        assertEquals(RolloutErrorCode.INVALID_CANARY, assertThrows(RolloutException.class, () -> Canary.start("c", POLICY,
                SemanticVersion.FIRST, SemanticVersion.FIRST, 10, null, null, Optional.empty(), "ana", NOW)).code());
        assertEquals(RolloutErrorCode.INVALID_CANARY, assertThrows(RolloutException.class, () ->
                new CanaryCriteria(-1, 1, 1, 0)).code());
    }

    @Test
    void canaryLifecycleAndAccessors() {
        Canary canary = Canary.restore(canary(null).snapshot());

        assertEquals("c-1", canary.id());
        assertEquals(POLICY, canary.policy());
        assertEquals(CANDIDATE, canary.candidate());
        assertEquals(SemanticVersion.FIRST, canary.baseline());
        assertEquals(Optional.of("s-1"), canary.shadowRef());
        assertEquals(NOW, canary.startedAt());
        assertEquals(0, canary.revision());
        assertEquals(CanaryCriteria.GLOBAL_DEFAULT, canary.criteria());
        assertFalse(canary.awaitingExtension());
        canary.end("criteria did not pass", NOW);
        assertEquals(CanaryStatus.ENDED, canary.status());
        assertEquals(RolloutErrorCode.CANARY_NOT_ACTIVE, assertThrows(RolloutException.class, () -> canary.cancel("x", NOW)).code());
        assertEquals(RolloutErrorCode.CANARY_NOT_ACTIVE, assertThrows(RolloutException.class, canary::beginConclusion).code());
        Canary cancelled = canary(null);
        cancelled.cancel("operator", NOW);
        assertEquals(CanaryStatus.CANCELLED, cancelled.status());
    }

    @Test
    void evaluatorFlagsErrorRegressionsAndP99() {
        CanaryEvaluator evaluator = new CanaryEvaluator();
        Canary canary = canary(Duration.ofMinutes(10));
        VersionStats stable = new VersionStats(2_000, 0, 1_000, 1_000);
        VersionStats erroring = new VersionStats(2_000, 40, 1_000, 1_000);
        VersionStats slowTail = new VersionStats(2_000, 0, 1_000, 5_000);
        Instant after = NOW.plus(Duration.ofHours(1));

        CanaryVerdict.Abort errors = assertInstanceOf(CanaryVerdict.Abort.class, evaluator.evaluate(canary, stable, erroring, Optional.empty(), after));
        CanaryVerdict.Abort tail = assertInstanceOf(CanaryVerdict.Abort.class, evaluator.evaluate(canary, stable, slowTail, Optional.empty(), after));

        assertTrue(errors.reasons().get(0).startsWith("error rate"));
        assertTrue(tail.reasons().get(0).startsWith("p99"));
        assertEquals(0, VersionStats.EMPTY.errorRate());
    }

    @Test
    void shadowValidatesAndEnds() {
        assertEquals(RolloutErrorCode.INVALID_SHADOW, assertThrows(RolloutException.class, () -> shadow(0, null, List.of())).code());
        assertEquals(RolloutErrorCode.INVALID_SHADOW, assertThrows(RolloutException.class, () -> shadow(10, Duration.ofDays(8), List.of())).code());
        assertEquals(RolloutErrorCode.INVALID_SHADOW, assertThrows(RolloutException.class, () -> shadow(10, null, List.of("items"))).code());
        assertEquals(RolloutErrorCode.INVALID_SHADOW, assertThrows(RolloutException.class, () ->
                new VirtualRoute("crm", null, "/x", 42, null)).code());

        Shadow shadow = Shadow.restore(shadow(10, null, List.of("data.at")).snapshot());
        assertEquals("s-1", shadow.id());
        assertEquals(POLICY, shadow.policy());
        assertEquals(CANDIDATE, shadow.target());
        assertEquals(List.of("data.at"), shadow.ignoredPaths());
        assertEquals(List.of(), shadow.virtualRoutes());
        assertEquals(0, shadow.revision());
        assertTrue(shadow.isLiveAt(NOW));
        shadow.end(NOW);
        assertEquals(ShadowStatus.ENDED, shadow.statusAt(NOW));
        assertEquals(RolloutErrorCode.SHADOW_NOT_LIVE, assertThrows(RolloutException.class, () -> shadow.end(NOW)).code());
        assertEquals(RolloutErrorCode.SHADOW_NOT_LIVE, assertThrows(RolloutException.class, () -> shadow.resume(NOW)).code());
    }

    @Test
    void comparatorHandlesErrorPairsAndPreviewOutcomes() {
        ShadowComparator comparator = new ShadowComparator(List.of());
        ExecutionOutcome failedA = new ExecutionOutcome.Failed(ExecutionErrorCode.EXPRESSION_ERROR, "a");
        ExecutionOutcome failedB = new ExecutionOutcome.Failed(ExecutionErrorCode.DEADLINE_EXCEEDED, "b");
        ExecutionOutcome blocked = new ExecutionOutcome.PreviewBlocked("x");
        ExecutionOutcome ok = new ExecutionOutcome.Responded("done", 200, Map.of("items", List.of(Map.of("id", 1))));

        assertEquals(ComparisonResult.EQUAL, comparator.compare(failedA, failedA, false, List.of()));
        assertEquals(ComparisonResult.DIVERGENT_OUTCOME, comparator.compare(failedA, failedB, false, List.of()));
        assertEquals(ComparisonResult.NOT_COMPARABLE, comparator.compare(blocked, ok, false, List.of()));
        assertEquals(ComparisonResult.NOT_COMPARABLE, comparator.compare(ok, blocked, false, List.of()));
        assertEquals(ComparisonResult.EQUAL, comparator.compare(ok,
                new ExecutionOutcome.Responded("done", 200, Map.of("items", List.of(Map.of("id", 2)))), false, List.of("data.items[*]")));
        assertEquals(ComparisonResult.EQUAL, comparator.compare(ok, ok, false, List.of("data.missing.deep")));
    }

    @Test
    void statsComputeRates() {
        ShadowStats stats = new ShadowStats(Map.of(ComparisonResult.EQUAL, 3L, ComparisonResult.DIVERGENT_DATA, 1L,
                ComparisonResult.NOT_COMPARABLE, 4L, ComparisonResult.DROPPED, 9L), 1.0, 2.0);
        ShadowStats empty = new ShadowStats(Map.of(), 0, 0);

        assertEquals(0.75, stats.equalityRate());
        assertEquals(0.5, stats.notComparableRate());
        assertEquals(0.0, empty.equalityRate());
        assertEquals(1.0, stats.latencyRatioP50());
    }

    @Test
    void recordingCapturesCacheHitsAndReplaysThem() {
        ApiRequest request = ApiRequest.rest("crm", "GET", "/x", Map.of(), Map.of(), null, Duration.ofSeconds(1));
        ApiResponse cached = ApiResponse.of(200, "cached", Map.of());
        Map<String, ApiResponse> store = new HashMap<>();
        ResultCache realCache = new ResultCache() {
            @Override
            public Optional<ApiResponse> find(String signature, Duration maxAge) {
                return Optional.ofNullable(store.get(signature));
            }

            @Override
            public void store(String signature, ApiResponse response) {
                store.put(signature, response);
            }
        };
        IoRecording recording = new IoRecording(new IoBinding(any -> ApiResponse.failure("unused"), realCache));
        IoBinding binding = recording.binding();
        binding.resultCache().store(request.signature(), cached);
        binding.resultCache().find(request.signature(), Duration.ofSeconds(1));
        binding.resultCache().find("other", Duration.ofSeconds(1));

        VirtualIo virtualIo = new VirtualIo(recording.recorded(), List.of());
        assertEquals("cached", virtualIo.binding().apiGateway().call(request).body());
        assertEquals(Optional.empty(), virtualIo.binding().resultCache().find(request.signature(), Duration.ofSeconds(1)));
    }

    @Test
    void routesAndSelectorsHandleEdges() {
        assertEquals("canary", Route.CANARY.label());
        assertTrue(Route.STABLE.servesRealTraffic());
        assertFalse(Route.PINNED.servesRealTraffic());
        assertFalse(TrafficSelector.selects(Optional.of("a"), "salt", 0, new Random()));
        assertTrue(TrafficSelector.selects(Optional.of("a"), "salt", 100, new Random()));
        assertEquals("canary_active", RolloutErrorCode.CANARY_ACTIVE.code());
        assertTrue(new VirtualRoute("crm", "get", "*", 200, null)
                .matches(ApiRequest.rest("crm", "GET", "/any", Map.of(), Map.of(), null, Duration.ofSeconds(1))));
    }

    private static Canary canary(Duration duration) {
        return Canary.start("c-1", POLICY, CANDIDATE, SemanticVersion.FIRST, 10, duration, null, Optional.of("s-1"), "ana", NOW);
    }

    private static Shadow shadow(int percentage, Duration duration, List<String> ignored) {
        return Shadow.start("s-1", POLICY, CANDIDATE, percentage, duration, List.of(), ignored, "ana", NOW);
    }
}
