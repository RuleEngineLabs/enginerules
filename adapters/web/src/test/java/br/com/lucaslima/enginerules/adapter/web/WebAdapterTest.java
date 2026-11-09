package br.com.lucaslima.enginerules.adapter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import br.com.lucaslima.enginerules.adapter.web.admin.AuditController;
import br.com.lucaslima.enginerules.adapter.web.admin.PolicyAdminController;
import br.com.lucaslima.enginerules.adapter.web.admin.RolloutController;
import br.com.lucaslima.enginerules.adapter.web.common.ActorHeaders;
import br.com.lucaslima.enginerules.adapter.web.common.ApiExceptionHandler;
import br.com.lucaslima.enginerules.adapter.web.common.ErrorResponses;
import br.com.lucaslima.enginerules.adapter.web.engine.ExecutionController;
import br.com.lucaslima.enginerules.adapter.web.engine.PreviewController;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.MoveStableVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyQueries;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicySummary;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyVersionDetails;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishedVersion;
import br.com.lucaslima.enginerules.application.catalog.port.in.RemoveVersionUseCase;
import br.com.lucaslima.enginerules.application.execution.port.in.ExecutePolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.port.in.PreviewPolicyUseCase;
import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryView;
import br.com.lucaslima.enginerules.application.rollout.port.in.EvaluateCanariesUseCase;
import br.com.lucaslima.enginerules.application.rollout.port.in.RolloutQueries;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowReport;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowView;
import br.com.lucaslima.enginerules.application.shared.InvalidRequestException;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.StableMove;
import br.com.lucaslima.enginerules.domain.catalog.VersionStatus;
import br.com.lucaslima.enginerules.domain.definition.InvalidPolicyDocumentException;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.execution.CompilationViolation;
import br.com.lucaslima.enginerules.domain.execution.ExecutionErrorCode;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;
import br.com.lucaslima.enginerules.domain.execution.ExecutionReport;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompilationException;
import br.com.lucaslima.enginerules.domain.execution.TraceEntry;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;

class WebAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final PolicyName NAME = PolicyName.of("Gate");
    private static final Map<String, Object> DEFINITION = Map.of("entry", "done",
            "states", List.of(Map.of("id", "done", "kind", "response", "status", 200)));

    private final List<Object> commands = new ArrayList<>();

    @Test
    void executionControllerMapsHeadersAndEnvelopes() {
        ExecutionReport report = new ExecutionReport(new ExecutionOutcome.Responded("approved", 201, Map.of("ok", true)),
                List.of(), 1, Duration.ofMillis(1));
        ExecutionController controller = new ExecutionController(command -> {
            commands.add(command);
            return new ExecutePolicyUseCase.Result("Gate", "1.0.0", "stable", report);
        });

        ResponseEntity<Map<String, Object>> response = controller.execute("Gate", null, null, "1.0.0", false, "no-cache", "checkout");
        controller.execute("Gate", Map.of("x", 1), "2.0.0", null, true, null, null);

        assertEquals(201, response.getStatusCode().value());
        assertEquals("1.0.0", response.getHeaders().getFirst("X-Policy-Version"));
        assertEquals("stable", response.getHeaders().getFirst("X-Policy-Route"));
        assertEquals(Map.of("state", "approved", "data", Map.of("ok", true)), response.getBody());
        ExecutePolicyUseCase.Command first = (ExecutePolicyUseCase.Command) commands.get(0);
        ExecutePolicyUseCase.Command second = (ExecutePolicyUseCase.Command) commands.get(1);
        assertTrue(first.noCache());
        assertEquals(Optional.of("checkout"), first.consumerId());
        assertEquals("2.0.0", second.requestedVersion());
        assertTrue(second.noCache());
    }

    @Test
    void previewControllerValidatesTheRequestShape() {
        ExecutionReport report = new ExecutionReport(new ExecutionOutcome.PreviewBlocked("create"), List.of(new TraceEntry(
                "create", "apiCall", null, 3, List.of(new TraceEntry.Attempt(503, null, false, 2)), true,
                Map.of("status=503", List.of(0)))), 1, Duration.ofMillis(1));
        PreviewController controller = new PreviewController(command -> new PreviewPolicyUseCase.Result(List.of("create"), report));

        ResponseEntity<Map<String, Object>> response = controller.preview(Map.of("policy", DEFINITION));

        Map<String, Object> body = response.getBody();
        assertEquals(List.of("create"), body.get("writeStates"));
        assertEquals("preview_blocked_write", ((Map<?, ?>) body.get("outcome")).get("type"));
        Map<?, ?> trace = (Map<?, ?>) ((List<?>) body.get("trace")).get(0);
        assertEquals(true, trace.get("resultFromCache"));
        assertTrue(trace.containsKey("attempts") && trace.containsKey("failedItems"));
        assertThrows(InvalidRequestException.class, () -> controller.preview(Map.of("input", Map.of())));
        assertThrows(InvalidRequestException.class, () -> controller.preview(Map.of("policy", DEFINITION, "input", "x")));
    }

    @Test
    void policyAdminControllerTranslatesEveryRoute() {
        Policy policy = Policy.create(NAME, "credit-team", Set.of(), new ContentHash("a".repeat(64)),
                new Actor("ana", Set.of("credit-team")), NOW);
        PolicySummary summary = PolicySummary.of(policy);
        PolicyQueries queries = new PolicyQueries() {
            @Override
            public PolicySummary findPolicy(String name) {
                return summary;
            }

            @Override
            public PolicyVersionDetails findVersion(String name, String version) {
                return new PolicyVersionDetails("Gate", "1.0.0", VersionStatus.STABLE, "a".repeat(64), "ana", NOW,
                        new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(200).build())));
            }

            @Override
            public Page<PolicySummary> listPolicies(
                    br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter filter,
                    br.com.lucaslima.enginerules.application.shared.PageRequest page) {
                return new Page<>(List.of(summary), 1, 20, 1);
            }
        };
        PolicyAdminController controller = new PolicyAdminController(
                command -> new PublishedVersion("Gate", "1.0.0", "h", true),
                command -> new PublishedVersion("Gate", "1.0.1", "h", command.basedOn().equals("1.0.0")),
                command -> new StableMove(SemanticVersion.FIRST, SemanticVersion.parse("1.0.1"), StableMove.Kind.PROMOTION, Optional.empty()),
                command -> commands.add(command),
                queries);

        assertEquals(201, controller.create(Map.of("name", "Gate", "ownerGroup", "credit-team", "allowedConsumers", List.of("a"),
                "definition", DEFINITION), "ana", "credit-team").getStatusCode().value());
        assertEquals(201, controller.publish("Gate", Map.of("basedOn", "1.0.0", "definition", DEFINITION), "ana", "credit-team")
                .getStatusCode().value());
        assertEquals(200, controller.publish("Gate", Map.of("basedOn", "0.9.0", "definition", DEFINITION), "ana", null)
                .getStatusCode().value());
        assertEquals("PROMOTION", controller.moveStable("Gate", Map.of("version", "1.0.1"), "ana", "a, ,b").get("kind"));
        assertEquals(204, controller.remove("Gate", "1.0.1", "ana", null).getStatusCode().value());
        assertEquals(1L, controller.list(null, null, null, null, null).get("totalItems"));
        assertEquals("STABLE", ((Map<?, ?>) ((List<?>) controller.find("Gate").get("versions")).get(0)).get("status"));
        assertEquals("STABLE", controller.findVersion("Gate", "1.0.0").get("status"));
        assertThrows(InvalidRequestException.class, () -> controller.create(Map.of("ownerGroup", "x", "definition", DEFINITION), "ana", null));
        assertThrows(InvalidRequestException.class, () -> controller.create(Map.of("name", "Gate", "ownerGroup", "x"), "ana", null));
        assertThrows(InvalidRequestException.class, () -> controller.create(Map.of("name", "Gate", "ownerGroup", "x",
                "allowedConsumers", "a", "definition", DEFINITION), "ana", null));
        assertThrows(InvalidRequestException.class, () -> controller.moveStable("Gate", Map.of(), " ", null));
    }

    @Test
    void auditAndRolloutControllersTranslateEveryRoute() {
        AuditController audits = new AuditController((filter, page) ->
                new Page<>(List.of(new AuditRecord(NOW, "Gate", "ana", "policy.created", Map.of("v", "1"))), 1, 20, 1));
        assertEquals(1L, audits.list("Gate", null, null, 1, 20).get("totalItems"));

        Canary canary = Canary.start("c-1", NAME, SemanticVersion.parse("1.0.1"), SemanticVersion.FIRST, 10, null, null,
                Optional.of("s-1"), "ana", NOW);
        Shadow shadow = Shadow.start("s-1", NAME, SemanticVersion.parse("1.0.1"), 10, null, List.of(), List.of(), "ana", NOW);
        ShadowView shadowView = new ShadowView(shadow.snapshot(), shadow.statusAt(NOW));
        CanaryView canaryView = CanaryView.of(canary);
        CanaryUseCases canaryUseCases = new CanaryUseCases() {
            public CanaryView start(StartCommand command) { commands.add(command); return canaryView; }
            public CanaryView cancel(String policy, Actor actor, String reason) { commands.add("cancel"); return canaryView; }
            public CanaryView end(String policy, Actor actor, String reason) { commands.add("end"); return canaryView; }
            public CanaryView extend(String policy, Actor actor, Duration by) { commands.add(by); return canaryView; }
        };
        ShadowUseCases shadowUseCases = new ShadowUseCases() {
            public ShadowView start(StartCommand command) { commands.add(command); return shadowView; }
            public ShadowView pause(String policy, String id, Actor actor) { commands.add("pause"); return shadowView; }
            public ShadowView resume(String policy, String id, Actor actor) { commands.add("resume"); return shadowView; }
            public ShadowView end(String policy, String id, Actor actor) { commands.add("endShadow"); return shadowView; }
        };
        RolloutQueries queries = new RolloutQueries() {
            public List<CanaryView> canaries(String policy) { return List.of(canaryView); }
            public List<ShadowView> shadows(String policy) { return List.of(shadowView); }
            public ShadowReport shadowReport(String policy, String id) {
                return new ShadowReport(shadowView, new ShadowStats(Map.of(ComparisonResult.EQUAL, 2L), 1.0, 1.1));
            }
        };
        EvaluateCanariesUseCase evaluations = () -> List.of(new EvaluateCanariesUseCase.Evaluation("Gate", "c-1", "observing", List.of()));
        RolloutController controller = new RolloutController(canaryUseCases, shadowUseCases, queries, evaluations);

        assertEquals(201, controller.startCanary("Gate", Map.of("candidate", "1.0.1", "weight", 10, "durationMinutes", 30,
                "criteria", Map.of("minSamples", 50), "shadowRef", "s-1"), "ana", null).getStatusCode().value());
        assertEquals(201, controller.startCanary("Gate", Map.of("candidate", "1.0.1", "weight", 10), "ana", null).getStatusCode().value());
        assertEquals("c-1", controller.listCanaries("Gate").get(0).get("id"));
        controller.changeCanary("Gate", Map.of("extendMinutes", 60), "ana", null);
        controller.changeCanary("Gate", Map.of("status", "ENDED"), "ana", null);
        controller.changeCanary("Gate", Map.of("status", "CANCELLED", "reason", "x"), "ana", null);
        controller.cancelCanary("Gate", "ana", null);
        assertThrows(InvalidRequestException.class, () -> controller.changeCanary("Gate", Map.of("status", "WHAT"), "ana", null));
        assertThrows(InvalidRequestException.class, () -> controller.startCanary("Gate", Map.of("candidate", "1.0.1"), "ana", null));
        assertEquals("observing", controller.evaluateCanaries().get(0).get("verdict"));
        assertEquals(201, controller.startShadow("Gate", Map.of("target", "1.0.1", "percentage", 10, "ignoredPaths", List.of("data.at"),
                "virtualRoutes", List.of(Map.of("connection", "crm", "method", "GET", "pathTemplate", "/x", "body", Map.of()))),
                "ana", null).getStatusCode().value());
        assertThrows(InvalidRequestException.class, () -> controller.startShadow("Gate", Map.of("target", "1.0.1", "percentage", 10,
                "virtualRoutes", List.of("x")), "ana", null));
        assertEquals("s-1", controller.listShadows("Gate").get(0).get("id"));
        controller.changeShadow("Gate", "s-1", Map.of("status", "PAUSED"), "ana", null);
        controller.changeShadow("Gate", "s-1", Map.of("status", "ACTIVE"), "ana", null);
        controller.changeShadow("Gate", "s-1", Map.of("status", "ENDED"), "ana", null);
        assertThrows(InvalidRequestException.class, () -> controller.changeShadow("Gate", "s-1", Map.of("status", "X"), "ana", null));
        assertEquals(1.0, controller.shadowReport("Gate", "s-1").get("equalityRate"));
        assertTrue(commands.containsAll(List.of(Duration.ofMinutes(60), "end", "cancel", "pause", "resume", "endShadow")));
    }

    @Test
    void exceptionHandlerMapsEveryFailureFamily() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        assertEquals(404, handler.catalog(new CatalogException(CatalogErrorCode.POLICY_NOT_FOUND, "x")).getStatusCode().value());
        assertEquals(409, handler.rollout(new RolloutException(RolloutErrorCode.CANARY_ACTIVE, "x")).getStatusCode().value());
        assertEquals(422, handler.compilation(new PolicyCompilationException(List.of(new CompilationViolation("a", "b"))))
                .getStatusCode().value());
        assertEquals(400, handler.document(new InvalidPolicyDocumentException(List.of("bad"))).getStatusCode().value());
        assertEquals(400, handler.invalidRequest(new InvalidRequestException("bad")).getStatusCode().value());
        for (CatalogErrorCode code : CatalogErrorCode.values()) {
            assertTrue(ErrorResponses.statusOf(code) >= 400);
        }
        for (RolloutErrorCode code : RolloutErrorCode.values()) {
            assertTrue(ErrorResponses.statusOf(code) >= 400);
        }
        assertThrows(InvalidRequestException.class, () -> ActorHeaders.resolve(null, null));
        assertEquals(Set.of(), ActorHeaders.resolve("ana", null).groups());
    }

    @Test
    void executionResponsesCoverFailedAndBlockedOutcomes() {
        assertEquals(500, br.com.lucaslima.enginerules.adapter.web.engine.ExecutionResponses.httpStatus(
                new ExecutionOutcome.Failed(ExecutionErrorCode.DEADLINE_EXCEEDED, "x")));
        assertEquals(200, br.com.lucaslima.enginerules.adapter.web.engine.ExecutionResponses.httpStatus(
                new ExecutionOutcome.PreviewBlocked("x")));
        Map<String, Object> failed = br.com.lucaslima.enginerules.adapter.web.engine.ExecutionResponses.preview(List.of(),
                new ExecutionReport(new ExecutionOutcome.Failed(ExecutionErrorCode.DEADLINE_EXCEEDED, "x"), List.of(), 0, Duration.ZERO));
        Map<String, Object> responded = br.com.lucaslima.enginerules.adapter.web.engine.ExecutionResponses.preview(List.of(),
                new ExecutionReport(new ExecutionOutcome.Responded("a", 200, null), List.of(), 0, Duration.ZERO));
        assertEquals("failed", ((Map<?, ?>) failed.get("outcome")).get("type"));
        assertEquals("responded", ((Map<?, ?>) responded.get("outcome")).get("type"));
    }
}
