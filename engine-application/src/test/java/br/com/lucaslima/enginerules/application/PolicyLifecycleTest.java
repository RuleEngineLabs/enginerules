package br.com.lucaslima.enginerules.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.MoveStableVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishedVersion;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.catalog.service.CreatePolicyService;
import br.com.lucaslima.enginerules.application.catalog.service.MoveStableVersionService;
import br.com.lucaslima.enginerules.application.catalog.service.PublishVersionService;
import br.com.lucaslima.enginerules.application.execution.ExecutionLimits;
import br.com.lucaslima.enginerules.application.execution.port.in.ExecutePolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.service.ExecutePolicyService;
import br.com.lucaslima.enginerules.application.execution.service.ShadowDispatcher;
import br.com.lucaslima.enginerules.application.execution.service.TrafficRouter;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.ChangeClassifier;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.definition.Transition;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompilationException;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.execution.PolicyExecutor;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowComparator;

class PolicyLifecycleTest {

    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);

    private final InMemoryCatalog store = new InMemoryCatalog();
    private final CatalogLookup catalog = new CatalogLookup(store, store);
    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage());
    private final CreatePolicyUseCase create = new CreatePolicyService(store, store, store, compiler, CLOCK);
    private final PublishVersionUseCase publish = new PublishVersionService(catalog, store, store, compiler,
            new ChangeClassifier(), CLOCK);
    private final InMemoryRollouts rollouts = new InMemoryRollouts();
    private final MoveStableVersionUseCase moveStable = new MoveStableVersionService(catalog, store, store, rollouts, CLOCK);
    private final PolicyExecutor executor = new PolicyExecutor(request -> ApiResponse.failure("no I/O in this test"), noCache());
    private final ExecutionLimits limits = new ExecutionLimits(Duration.ofSeconds(2), 100);
    private final ExecutePolicyUseCase execute = new ExecutePolicyService(catalog, store, name -> false, compiler, executor,
            limits, new TrafficRouter(rollouts, new Random(1)),
            new ShadowDispatcher(rollouts, rollouts, rollouts, catalog, store, compiler, executor,
                    new ShadowComparator(List.of()), limits, new Random(1), CLOCK),
            rollouts, CLOCK);

    @Test
    void createsPublishesPromotesAndExecutes() {
        create.create(new CreatePolicyUseCase.Command("AgeGate", "credit-team", Set.of(), gate(18), ANA));
        assertEquals("approved", executeFor(20, null));

        PublishedVersion candidate = publish.publish(new PublishVersionUseCase.Command("AgeGate", "1.0.0", gate(21), ANA));
        assertEquals("1.0.1", candidate.version());
        assertEquals("approved", executeFor(20, null));

        CatalogException notYet = assertThrows(CatalogException.class, () -> executeFor(20, "1.0.1"));
        assertEquals(CatalogErrorCode.PENDING_CANDIDATE_NOT_EXECUTABLE, notYet.code());

        moveStable.moveStable(new MoveStableVersionUseCase.Command("AgeGate", "1.0.1", ANA));
        assertEquals("denied", executeFor(20, null));
        assertEquals("approved", executeFor(20, "1.0.0"));

        assertEquals(List.of("policy.created", "version.published", "stable.moved"),
                store.audits().stream().map(record -> record.action()).toList());
    }

    @Test
    void refusesToPublishAPolicyThatDoesNotCompile() {
        create.create(new CreatePolicyUseCase.Command("AgeGate", "credit-team", Set.of(), gate(18), ANA));
        PolicyDefinition broken = new PolicyDefinition("missing", gate(18).states());

        assertThrows(PolicyCompilationException.class, () ->
                publish.publish(new PublishVersionUseCase.Command("AgeGate", "1.0.0", broken, ANA)));
        assertTrue(store.loadPolicy(PolicyName.of("AgeGate"), ReadConsistency.DIRECT)
                .orElseThrow().pendingCandidate().isEmpty());
    }

    private String executeFor(int age, String version) {
        ExecutePolicyUseCase.Result result = execute.execute(new ExecutePolicyUseCase.Command(
                "AgeGate", version, Map.of("age", age), Optional.empty(), false));
        return assertInstanceOf(ExecutionOutcome.Responded.class, result.report().outcome()).state();
    }

    private static PolicyDefinition gate(int minimumAge) {
        return new PolicyDefinition("evaluate", List.of(
                StateDefinition.builder("evaluate", "execution")
                        .transitions(List.of(new Transition("input.age >= " + minimumAge, "approved")))
                        .fallback("denied")
                        .build(),
                StateDefinition.builder("approved", "response").status(200).build(),
                StateDefinition.builder("denied", "response").status(403).build()));
    }

    private static ResultCache noCache() {
        return new ResultCache() {
            @Override
            public Optional<ApiResponse> find(String signature, Duration maxAge) {
                return Optional.empty();
            }

            @Override
            public void store(String signature, ApiResponse response) {
            }
        };
    }
}
