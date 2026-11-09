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
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.MoveStableVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.RemoveVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.catalog.service.CreatePolicyService;
import br.com.lucaslima.enginerules.application.catalog.service.MoveStableVersionService;
import br.com.lucaslima.enginerules.application.catalog.service.PublishVersionService;
import br.com.lucaslima.enginerules.application.catalog.service.RemoveVersionService;
import br.com.lucaslima.enginerules.application.execution.ExecutionLimits;
import br.com.lucaslima.enginerules.application.execution.port.in.ExecutePolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.service.ExecutePolicyService;
import br.com.lucaslima.enginerules.application.execution.service.ShadowDispatcher;
import br.com.lucaslima.enginerules.application.execution.service.TrafficRouter;
import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.in.EvaluateCanariesUseCase;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowUseCases;
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
import br.com.lucaslima.enginerules.domain.definition.Transition;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.execution.PolicyExecutor;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryEvaluator;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowComparator;

class RolloutLifecycleTest {

    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));
    private static final CanaryCriteria SMALL_SAMPLES = new CanaryCriteria(0.5, 1.2, 20, 0.0);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T12:00:00Z"));
    private final InMemoryCatalog store = new InMemoryCatalog();
    private final InMemoryRollouts rollouts = new InMemoryRollouts();
    private final CatalogLookup catalog = new CatalogLookup(store, store);
    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage());
    private final PolicyExecutor executor = new PolicyExecutor(
            request -> ApiResponse.of(200, Map.of("score", 700), Map.of()), ResultCache.disabled());
    private final ExecutionLimits limits = new ExecutionLimits(Duration.ofSeconds(2), 100);
    private final CreatePolicyUseCase create = new CreatePolicyService(store, store, store, compiler, clock);
    private final PublishVersionUseCase publish = new PublishVersionService(catalog, store, store, compiler,
            new ChangeClassifier(), clock);
    private final MoveStableVersionUseCase moveStable = new MoveStableVersionService(catalog, store, store, rollouts, clock);
    private final RemoveVersionUseCase remove = new RemoveVersionService(catalog, store, store, rollouts, clock);
    private final CanaryUseCases canaries = new CanaryService(catalog, rollouts, rollouts, new ChangeClassifier(),
            CanaryCriteria.GLOBAL_DEFAULT, store, clock);
    private final ShadowUseCases shadows = new ShadowService(catalog, rollouts, rollouts, store, clock);
    private final EvaluateCanariesUseCase evaluations = new CanaryEvaluationService(rollouts, rollouts, rollouts, catalog,
            store, store, new CanaryEvaluator(), clock);
    private final ExecutePolicyUseCase execute = new ExecutePolicyService(catalog, store, name -> false, compiler, executor,
            limits, new TrafficRouter(rollouts, new Random(3)),
            new ShadowDispatcher(rollouts, rollouts, rollouts, catalog, store, compiler, executor,
                    new ShadowComparator(List.of()), limits, new Random(3), clock),
            rollouts, clock);

    @Test
    void canarySplitsTrafficBlocksManualMovesAndPromotesAfterTheDeadline() {
        createWithCandidate();
        canaries.start(new CanaryUseCases.StartCommand("Scoring", "1.0.1", 50, Duration.ofMinutes(30),
                Optional.of(SMALL_SAMPLES), Optional.empty(), ANA));

        long toCandidate = executeForManyConsumers(200).stream().filter("canary"::equals).count();
        assertTrue(toCandidate > 60 && toCandidate < 140, "candidate got " + toCandidate);
        assertEquals("pinned", execute("1.0.0", "c-1").route());

        CatalogException blocked = assertThrows(CatalogException.class, () ->
                moveStable.moveStable(new MoveStableVersionUseCase.Command("Scoring", "1.0.1", ANA)));
        assertEquals(CatalogErrorCode.CANARY_IN_PROGRESS, blocked.code());
        CatalogException inUse = assertThrows(CatalogException.class, () ->
                remove.remove(new RemoveVersionUseCase.Command("Scoring", "1.0.1", ANA)));
        assertEquals(CatalogErrorCode.VERSION_IN_USE, inUse.code());

        assertEquals("observing", evaluations.evaluateOpenCanaries().get(0).verdict());
        clock.advance(Duration.ofMinutes(31));
        assertEquals("promoted", evaluations.evaluateOpenCanaries().get(0).verdict());

        assertEquals(SemanticVersion.parse("1.0.1"),
                store.loadPolicy(PolicyName.of("Scoring"), ReadConsistency.DIRECT).orElseThrow().stable());
        assertEquals("stable", execute(null, "c-1").route());
    }

    @Test
    void refusesCanariesForAnythingButThePendingCandidate() {
        createWithCandidate();

        RolloutException failure = assertThrows(RolloutException.class, () -> canaries.start(new CanaryUseCases.StartCommand(
                "Scoring", "1.0.0", 10, null, Optional.empty(), Optional.empty(), ANA)));

        assertEquals(RolloutErrorCode.CANDIDATE_NOT_PENDING, failure.code());
    }

    @Test
    void shadowReplaysRealIoAndReportsTheComparison() {
        createWithCandidate();
        String shadowId = shadows.start(new ShadowUseCases.StartCommand("Scoring", "1.0.1", 100, null, List.of(), List.of(), ANA))
                .snapshot().id();

        executeForManyConsumers(10);
        rollouts.rejectShadowCopies();
        executeForManyConsumers(2);

        var report = new RolloutQueryService(rollouts, rollouts, rollouts, clock).shadowReport("Scoring", shadowId);
        assertEquals(10L, report.stats().count(ComparisonResult.DIVERGENT_OUTCOME) + report.stats().count(ComparisonResult.EQUAL));
        assertEquals(2L, report.stats().count(ComparisonResult.DROPPED));
        assertTrue(rollouts.executions().stream().allMatch(sample -> sample.route() == Route.STABLE));
    }

    private void createWithCandidate() {
        create.create(new CreatePolicyUseCase.Command("Scoring", "credit-team", Set.of(), scoring(600), ANA));
        publish.publish(new PublishVersionUseCase.Command("Scoring", "1.0.0", scoring(650), ANA));
    }

    private List<String> executeForManyConsumers(int consumers) {
        return IntStream.range(0, consumers)
                .mapToObj(index -> execute(null, "consumer-" + index).route())
                .toList();
    }

    private ExecutePolicyUseCase.Result execute(String version, String consumer) {
        return execute.execute(new ExecutePolicyUseCase.Command("Scoring", version, Map.of("document", "123"),
                Optional.of(consumer), false));
    }

    private static PolicyDefinition scoring(int threshold) {
        return new PolicyDefinition("score", List.of(
                StateDefinition.builder("score", "apiCall").connection("bureau").method("GET").path("/scores/{document}")
                        .pathParams(Map.of("document", "input.document")).contextKey("bureau")
                        .transitions(List.of(new Transition("bureau.body.score >= " + threshold, "approved")))
                        .fallback("denied").build(),
                StateDefinition.builder("approved", "response").status(200).data(Map.of("score", "bureau.body.score")).build(),
                StateDefinition.builder("denied", "response").status(200).data(Map.of("score", "bureau.body.score")).build()));
    }
}
