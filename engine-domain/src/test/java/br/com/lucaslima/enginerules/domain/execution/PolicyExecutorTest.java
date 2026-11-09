package br.com.lucaslima.enginerules.domain.execution;

import static br.com.lucaslima.enginerules.domain.fixtures.Policies.ageGate;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.apiCall;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.execution;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.parallel;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.policy;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.response;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.RetryDefinition;
import br.com.lucaslima.enginerules.domain.definition.Transition;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.fixtures.ScriptedApiGateway;

class PolicyExecutorTest {

    private static final ExecutionOptions LIVE = ExecutionOptions.live(true, Duration.ofSeconds(5), 100);

    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage());
    private final ScriptedApiGateway gateway = new ScriptedApiGateway();
    private final PolicyExecutor executor = new PolicyExecutor(gateway, gateway);

    @Test
    void walksTransitionsAndRendersTheResponseData() {
        ExecutionReport report = run(ageGate(), Map.of("age", 30, "name", "Ana"), LIVE);

        ExecutionOutcome.Responded responded = assertInstanceOf(ExecutionOutcome.Responded.class, report.outcome());
        assertEquals("approved", responded.state());
        assertEquals(200, responded.status());
        assertEquals(Map.of("approved", true, "name", "Ana"), responded.data());
        assertEquals(2, report.trace().size());
    }

    @Test
    void takesTheFallbackWhenNoTransitionMatches() {
        ExecutionReport report = run(ageGate(), Map.of("age", 15), LIVE);

        assertEquals("denied", assertInstanceOf(ExecutionOutcome.Responded.class, report.outcome()).state());
    }

    @Test
    void routesApiCallsByResultAndRetriesRetryableStatuses() {
        gateway.thenRespond(ApiResponse.of(503, null, Map.of())).thenRespond(ApiResponse.of(200, Map.of("score", 900), Map.of()));
        PolicyDefinition definition = policy("score",
                apiCall("score").connection("bureau").method("GET").path("/scores/{document}")
                        .pathParams(Map.of("document", "input.document"))
                        .retry(new RetryDefinition(3, List.of(0L)))
                        .contextKey("bureau")
                        .transitions(List.of(
                                new Transition("result.timeout == true", "providerTimeout"),
                                new Transition("result.status >= 500", "providerUnavailable"),
                                new Transition("result.status == 200", "approved")))
                        .fallback("invalidRequest")
                        .build(),
                response("approved", 200, Map.of("score", "bureau.body.score")),
                response("providerTimeout", 504, null),
                response("providerUnavailable", 503, null),
                response("invalidRequest", 400, null));

        ExecutionReport report = run(definition, Map.of("document", "123"), LIVE);

        ExecutionOutcome.Responded responded = assertInstanceOf(ExecutionOutcome.Responded.class, report.outcome());
        assertEquals("approved", responded.state());
        assertEquals(Map.of("score", 900), responded.data());
        assertEquals(2, gateway.requests().size());
        assertEquals(Map.of("document", "123"), gateway.requests().get(0).pathParams());
        assertEquals(2, report.trace().get(0).attempts().size());
    }

    @Test
    void servesCacheableGetCallsFromTheResultCache() {
        PolicyDefinition definition = policy("lookup",
                apiCall("lookup").connection("cep").method("GET").path("/cep/{cep}").pathParams(Map.of("cep", "input.cep"))
                        .ttlSeconds(60).fallback("done").build(),
                response("done", 200, null));

        run(definition, Map.of("cep", "01001000"), LIVE);
        ExecutionReport second = run(definition, Map.of("cep", "01001000"), LIVE);

        assertEquals(1, gateway.requests().size());
        assertTrue(second.trace().get(0).resultFromCache());
    }

    @Test
    void fansOutWithBoundedConcurrencyAndKeepsItemOrder() {
        gateway.withLatency(Duration.ofMillis(20)).otherwise(request ->
                ApiResponse.of(Integer.parseInt(request.pathParams().get("id")) == 3 ? 503 : 200,
                        Map.of("id", request.pathParams().get("id")), Map.of()));
        PolicyDefinition definition = policy("fanOut",
                parallel("fanOut").over("input.ids").each("fetch").maxConcurrency(2).contextKey("results")
                        .fallback("done").build(),
                apiCall("fetch").connection("items").method("GET").path("/items/{id}").pathParams(Map.of("id", "item")).build(),
                response("done", 200, Map.of("statuses", "map(results, #.status)")));

        ExecutionReport report = run(definition, Map.of("ids", List.of(1, 2, 3, 4, 5)), LIVE);

        ExecutionOutcome.Responded responded = assertInstanceOf(ExecutionOutcome.Responded.class, report.outcome());
        assertEquals(Map.of("statuses", List.of(200, 200, 503, 200, 200)), responded.data());
        assertTrue(gateway.peakInFlight() <= 2, "at most maxConcurrency calls may run at once");
        assertEquals(Map.of("status=503", List.of(2)), report.trace().get(0).failedItemsBySignature());
    }

    @Test
    void failsWithInvalidParallelSourceWhenItemsContainNull() {
        List<Object> items = new ArrayList<>();
        items.add(1);
        items.add(null);
        PolicyDefinition definition = policy("fanOut",
                parallel("fanOut").over("input.items").each("double").maxConcurrency(2).fallback("done").build(),
                execution("double").map(Map.of("value", "item * 2")).build(),
                response("done", 200, null));

        ExecutionReport report = run(definition, Map.of("items", items), LIVE);

        assertEquals(ExecutionErrorCode.INVALID_PARALLEL_SOURCE,
                assertInstanceOf(ExecutionOutcome.Failed.class, report.outcome()).error());
    }

    @Test
    void abortsWhenAGuardedContextFieldIsAbsentAtRuntime() {
        PolicyDefinition definition = policy("branch",
                execution("branch").transitions(when("input.vip", "markVip")).fallback("check").build(),
                execution("markVip").map(Map.of("tier", "'gold'")).contextKey("profile").fallback("check").build(),
                execution("check").transitions(when("profile.tier == 'gold'", "gold")).fallback("standard").build(),
                response("gold", 200, null),
                response("standard", 200, null));

        ExecutionReport report = run(definition, Map.of("vip", false), LIVE);

        assertEquals(ExecutionErrorCode.MISSING_CONTEXT_FIELD,
                assertInstanceOf(ExecutionOutcome.Failed.class, report.outcome()).error());
    }

    @Test
    void stopsAtWriteCallsInPreview() {
        PolicyDefinition definition = policy("create",
                apiCall("create").connection("orders").method("POST").path("/orders").fallback("done").build(),
                response("done", 201, null));

        ExecutionReport report = run(definition, Map.of(), ExecutionOptions.preview(Duration.ofSeconds(5), 100));

        assertEquals("create", assertInstanceOf(ExecutionOutcome.PreviewBlocked.class, report.outcome()).state());
        assertTrue(gateway.requests().isEmpty());
    }

    @Test
    void enforcesTheStepBudgetOnLoops() {
        PolicyDefinition definition = policy("spin",
                execution("spin").transitions(when("true", "spin")).fallback("done").build(),
                response("done", 200, null));

        ExecutionReport report = run(definition, Map.of(), ExecutionOptions.live(false, Duration.ofSeconds(5), 10));

        assertEquals(ExecutionErrorCode.STEP_BUDGET_EXCEEDED,
                assertInstanceOf(ExecutionOutcome.Failed.class, report.outcome()).error());
    }

    private ExecutionReport run(PolicyDefinition definition, Map<String, Object> input, ExecutionOptions options) {
        return executor.execute(compiler.compile(definition), input, options);
    }
}
