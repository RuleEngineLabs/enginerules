package br.com.lucaslima.enginerules.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.catalog.service.CreatePolicyService;
import br.com.lucaslima.enginerules.application.catalog.service.PublishVersionService;
import br.com.lucaslima.enginerules.application.execution.ExecutionLimits;
import br.com.lucaslima.enginerules.application.execution.port.in.ExecutePolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.service.ExecutePolicyService;
import br.com.lucaslima.enginerules.application.execution.service.ShadowDispatcher;
import br.com.lucaslima.enginerules.application.execution.service.TrafficRouter;
import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.in.EvaluateCanariesUseCase;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.out.ExecutionSample;
import br.com.lucaslima.enginerules.application.rollout.service.CanaryEvaluationService;
import br.com.lucaslima.enginerules.application.rollout.service.CanaryService;
import br.com.lucaslima.enginerules.application.rollout.service.RolloutQueryService;
import br.com.lucaslima.enginerules.application.rollout.service.ShadowService;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.ChangeClassifier;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.execution.PolicyExecutor;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryEvaluator;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryStatus;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowComparator;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;

class RolloutServicesTest {

    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));
    private static final Actor EVE = new Actor("eve", Set.of("marketing"));

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T12:00:00Z"));
    private final InMemoryCatalog store = new InMemoryCatalog();
    private final InMemoryRollouts rollouts = new InMemoryRollouts();
    private final CatalogLookup catalog = new CatalogLookup(store, store);
    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage());
    private final CanaryUseCases canaries = new CanaryService(catalog, rollouts, rollouts, new ChangeClassifier(),
            CanaryCriteria.GLOBAL_DEFAULT, store, clock);
    private final ShadowUseCases shadows = new ShadowService(catalog, rollouts, rollouts, store, clock);
    private final EvaluateCanariesUseCase evaluations = new CanaryEvaluationService(rollouts, rollouts, rollouts, catalog,
            store, store, new CanaryEvaluator(), clock);
    private final RolloutQueryService queries = new RolloutQueryService(rollouts, rollouts, rollouts, clock);

    @Test
    void canaryCommandsRequireOwnershipAndAnOpenCanary() {
        publishCandidate(200);

        assertEquals(CatalogErrorCode.NOT_OWNER, assertThrows(CatalogException.class, () -> canaries.start(start(EVE))).code());
        assertEquals(RolloutErrorCode.CANARY_NOT_FOUND, assertThrows(RolloutException.class, () -> canaries.cancel("Gate", ANA, null)).code());
        canaries.start(start(ANA));
        assertEquals(RolloutErrorCode.CANARY_ACTIVE, assertThrows(RolloutException.class, () -> canaries.start(start(ANA))).code());
        assertEquals(RolloutErrorCode.CANARY_NOT_INCONCLUSIVE, assertThrows(RolloutException.class, () ->
                canaries.extend("Gate", ANA, Duration.ofHours(1))).code());
        assertEquals(CanaryStatus.ENDED, canaries.end("Gate", ANA, " ").snapshot().status());
        assertEquals("ended by ana", queries.canaries("Gate").get(0).snapshot().closingReason());
    }

    @Test
    void majorCandidatesAreBlockedFromCanaries() {
        publishCandidate(201);

        assertEquals(RolloutErrorCode.CANARY_MAJOR_BLOCKED, assertThrows(RolloutException.class, () ->
                canaries.start(start(ANA, "2.0.0"))).code());
        assertEquals(RolloutErrorCode.CANDIDATE_NOT_PENDING, assertThrows(RolloutException.class, () ->
                canaries.start(start(ANA, "1.0.0"))).code());
    }

    @Test
    void evaluationMarksInconclusiveAllowsExtensionAndAbortsOnFailure() {
        publishCandidate(200);
        canaries.start(start(ANA));
        clock.advance(Duration.ofMinutes(31));

        assertEquals("inconclusive", evaluations.evaluateOpenCanaries().get(0).verdict());
        assertEquals("inconclusive", evaluations.evaluateOpenCanaries().get(0).verdict());
        assertTrue(canaries.extend("Gate", ANA, Duration.ofHours(1)).snapshot().extension().equals(Duration.ofHours(1)));
        for (int index = 0; index < 150; index++) {
            rollouts.recordExecution(new ExecutionSample(clock.instant(), PolicyName.of("Gate"), SemanticVersion.parse("1.0.1"),
                    Route.CANARY, true, 10));
        }

        assertEquals("aborted", evaluations.evaluateOpenCanaries().get(0).verdict());
        assertEquals(CanaryStatus.CANCELLED, queries.canaries("Gate").get(0).snapshot().status());
        assertTrue(store.audits().stream().anyMatch(record -> record.action().equals("canary.aborted")));
    }

    @Test
    void evaluationResumesAnInterruptedPromotion() {
        publishCandidate(200);
        canaries.start(start(ANA));
        Canary concluding = rollouts.findOpen(PolicyName.of("Gate"), ReadConsistency.DIRECT).orElseThrow();
        concluding.beginConclusion();
        rollouts.save(concluding);

        EvaluateCanariesUseCase.Evaluation evaluation = evaluations.evaluateOpenCanaries().get(0);

        assertEquals("promoted", evaluation.verdict());
        assertEquals(List.of("resumed an interrupted promotion"), evaluation.reasons());
        assertEquals(SemanticVersion.parse("1.0.1"), catalog.requirePolicy(PolicyName.of("Gate"), ReadConsistency.DIRECT).stable());
    }

    @Test
    void shadowCommandsValidateTargetsAndLifecycle() {
        publishCandidate(200);

        assertEquals(RolloutErrorCode.INVALID_SHADOW, assertThrows(RolloutException.class, () -> shadows.start(shadow("1.0.0"))).code());
        assertEquals(RolloutErrorCode.INVALID_SHADOW, assertThrows(RolloutException.class, () -> shadows.start(shadow("9.9.9"))).code());
        assertEquals(CatalogErrorCode.NOT_OWNER, assertThrows(CatalogException.class, () -> shadows.start(
                new ShadowUseCases.StartCommand("Gate", "1.0.1", 10, null, List.of(), List.of(), EVE))).code());
        String id = shadows.start(shadow("1.0.1")).snapshot().id();
        assertEquals(RolloutErrorCode.SHADOW_ACTIVE, assertThrows(RolloutException.class, () -> shadows.start(shadow("1.0.1"))).code());

        assertEquals(ShadowStatus.PAUSED, shadows.pause("Gate", id, ANA).effectiveStatus());
        assertEquals(ShadowStatus.ACTIVE, shadows.resume("Gate", id, ANA).effectiveStatus());
        assertEquals(ShadowStatus.ENDED, shadows.end("Gate", id, ANA).effectiveStatus());
        assertEquals(RolloutErrorCode.SHADOW_NOT_FOUND, assertThrows(RolloutException.class, () -> shadows.pause("Gate", "ghost", ANA)).code());
        assertEquals(RolloutErrorCode.SHADOW_NOT_FOUND, assertThrows(RolloutException.class, () -> queries.shadowReport("Gate", "ghost")).code());
        assertEquals(1, queries.shadows("Gate").size());
    }

    @Test
    void shadowFailuresAreRecordedAsNotComparableAndNoCacheSkipsCopies() {
        publishCandidate(200);
        String id = shadows.start(shadow("1.0.1")).snapshot().id();
        PolicyExecutor executor = new PolicyExecutor(request -> ApiResponse.failure("none"), ResultCache.disabled());
        ExecutionLimits limits = new ExecutionLimits(Duration.ofSeconds(1), 50);
        InMemoryCatalog brokenCompiledCache = new InMemoryCatalog() {
            @Override
            public CompiledPolicy getOrCompile(PolicyName name, SemanticVersion version, Supplier<CompiledPolicy> compilation) {
                if (version.equals(SemanticVersion.parse("1.0.1"))) {
                    throw new IllegalStateException("cannot compile");
                }
                return compilation.get();
            }
        };
        ExecutePolicyUseCase execute = new ExecutePolicyService(catalog, brokenCompiledCache, name -> true, compiler, executor,
                limits, new TrafficRouter(rollouts, new Random(1)),
                new ShadowDispatcher(rollouts, rollouts, rollouts, catalog, brokenCompiledCache, compiler, executor,
                        new ShadowComparator(List.of()), limits, new Random(1), clock),
                rollouts, clock);

        execute.execute(new ExecutePolicyUseCase.Command("Gate", null, Map.of(), Optional.of("a"), false));
        ExecutePolicyUseCase.Result direct = execute.execute(new ExecutePolicyUseCase.Command("Gate", null, Map.of(),
                Optional.of("a"), true));

        assertEquals(1L, queries.shadowReport("Gate", id).stats().count(ComparisonResult.NOT_COMPARABLE));
        assertEquals("stable", direct.route());
        assertTrue(direct.report().trace().size() > 0);
    }

    private void publishCandidate(int candidateStatus) {
        new CreatePolicyService(store, store, store, compiler, clock)
                .create(new CreatePolicyUseCase.Command("Gate", "credit-team", Set.of(), gate(200, "x"), ANA));
        new PublishVersionService(catalog, store, store, compiler, new ChangeClassifier(), clock)
                .publish(new PublishVersionUseCase.Command("Gate", "1.0.0", gate(candidateStatus, "y"), ANA));
    }

    private static CanaryUseCases.StartCommand start(Actor actor) {
        return start(actor, "1.0.1");
    }

    private static CanaryUseCases.StartCommand start(Actor actor, String candidate) {
        return new CanaryUseCases.StartCommand("Gate", candidate, 20, Duration.ofMinutes(30), Optional.empty(), Optional.empty(), actor);
    }

    private static ShadowUseCases.StartCommand shadow(String target) {
        return new ShadowUseCases.StartCommand("Gate", target, 100, null, List.of(), List.of(), ANA);
    }

    private static PolicyDefinition gate(int status, String marker) {
        return new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(status)
                .data(Map.of("marker", "'" + marker + "'")).build()));
    }
}
