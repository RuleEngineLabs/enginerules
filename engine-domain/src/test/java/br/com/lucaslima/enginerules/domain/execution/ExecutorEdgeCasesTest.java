package br.com.lucaslima.enginerules.domain.execution;

import static br.com.lucaslima.enginerules.domain.fixtures.Policies.apiCall;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.execution;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.parallel;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.policy;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.response;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.RetryDefinition;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionEvaluationException;
import br.com.lucaslima.enginerules.domain.fixtures.ScriptedApiGateway;

class ExecutorEdgeCasesTest {

    private static final ExecutionOptions LIVE = ExecutionOptions.live(false, Duration.ofSeconds(5), 100);

    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage());
    private final ScriptedApiGateway gateway = new ScriptedApiGateway();
    private final PolicyExecutor executor = new PolicyExecutor(gateway, gateway);

    @Test
    void reportsExpressionFailuresAsExecutorErrors() {
        ExecutionOutcome.Failed failed = failed(run(policy("a",
                execution("a").map(Map.of("x", "input.name > 3")).fallback("done").build(),
                response("done", 200, null)), Map.of("name", "Ana"), LIVE));

        assertEquals(ExecutionErrorCode.EXPRESSION_ERROR, failed.error());
    }

    @Test
    void requiresBooleanTransitionConditions() {
        ExecutionOutcome.Failed failed = failed(run(policy("a",
                execution("a").transitions(when("input.count", "done")).fallback("done").build(),
                response("done", 200, null)), Map.of("count", 3), LIVE));

        assertTrue(failed.message().contains("must be boolean"));
    }

    @Test
    void abortsWhenAGuardedRootIsMissing() {
        ExecutionOutcome.Failed failed = failed(run(policy("a",
                execution("a").transitions(when("input.skip", "check")).fallback("write").build(),
                execution("write").map(Map.of("tier", "'x'")).contextKey("profile").fallback("check").build(),
                execution("check").map(Map.of("copy", "profile")).fallback("done").build(),
                response("done", 200, null)), Map.of("skip", true), LIVE));

        assertEquals(ExecutionErrorCode.MISSING_CONTEXT_FIELD, failed.error());
    }

    @Test
    void handlesEmptyAndExecutionFanOuts() {
        PolicyDefinition definition = policy("fan",
                parallel("fan").over("input.items").each("double").maxConcurrency(3).contextKey("doubled").fallback("done").build(),
                execution("double").map(Map.of("value", "item * 2")).build(),
                response("done", 200, Map.of("doubled", "map(doubled, #.value)")));

        assertEquals(Map.of("doubled", List.of()), responded(run(definition, Map.of("items", List.of()), LIVE)).data());
        assertEquals(Map.of("doubled", List.of(2L, 4L)), responded(run(definition, Map.of("items", List.of(1, 2)), LIVE)).data());
        assertEquals(ExecutionErrorCode.INVALID_PARALLEL_SOURCE, failed(run(definition, Map.of("items", "x"), LIVE)).error());
    }

    @Test
    void groupsParallelFailuresByTimeoutAndError() {
        gateway.thenRespond(ApiResponse.timedOut()).thenRespond(ApiResponse.failure("boom")).thenRespond(ApiResponse.of(200, null, null));
        ExecutionReport report = run(fanOutCalls("GET"), Map.of("ids", List.of(1, 2, 3)),
                ExecutionOptions.live(true, Duration.ofSeconds(5), 100));

        Map<String, List<Integer>> failures = report.trace().get(0).failedItemsBySignature();
        assertEquals(2, failures.size());
        assertTrue(failures.containsKey("timeout") && failures.containsKey("error"));
    }

    @Test
    void blocksWritesInsideFanOutsDuringPreview() {
        ExecutionReport report = run(fanOutCalls("POST"), Map.of("ids", List.of(1)), ExecutionOptions.preview(Duration.ofSeconds(5), 100));

        assertEquals("fetch", assertInstanceOf(ExecutionOutcome.PreviewBlocked.class, report.outcome()).state());
    }

    @Test
    void resolvesQueryParametersAndRejectsNullPathParameters() {
        PolicyDefinition definition = policy("call",
                apiCall("call").connection("api").method("GET").path("/users/{id}").pathParams(Map.of("id", "input.id"))
                        .query(Map.of("expand", "input.expand", "missing", "input.nothing")).fallback("done").build(),
                response("done", 200, null));

        run(definition, Map.of("id", 7, "expand", true), LIVE);

        assertEquals(Map.of("expand", "true"), gateway.requests().get(0).query());
        assertEquals(ExecutionErrorCode.INVALID_REQUEST_PARAMETER, failed(run(definition, Map.of(), LIVE)).error());
    }

    @Test
    void stopsWhenTheDeadlineOrBackoffDoesNotFit() {
        gateway.withLatency(Duration.ofMillis(80));
        PolicyDefinition twoCalls = policy("first",
                apiCall("first").connection("api").method("GET").path("/a").fallback("second").build(),
                apiCall("second").connection("api").method("GET").path("/b").fallback("done").build(),
                response("done", 200, null));
        PolicyDefinition retrying = policy("call",
                apiCall("call").connection("api").method("GET").path("/a").retry(new RetryDefinition(3, List.of(10_000L)))
                        .fallback("done").build(),
                response("done", 200, null));
        gateway.otherwise(request -> ApiResponse.of(503, null, null));

        assertEquals(ExecutionErrorCode.DEADLINE_EXCEEDED,
                failed(run(twoCalls, Map.of(), ExecutionOptions.live(false, Duration.ofMillis(60), 100))).error());
        assertEquals(ExecutionErrorCode.DEADLINE_EXCEEDED,
                failed(run(retrying, Map.of(), ExecutionOptions.live(false, Duration.ofSeconds(1), 100))).error());
    }

    @Test
    void timesOutSlowFanOuts() {
        gateway.withLatency(Duration.ofMillis(300));

        assertEquals(ExecutionErrorCode.DEADLINE_EXCEEDED, failed(run(fanOutCalls("GET"), Map.of("ids", List.of(1, 2)),
                ExecutionOptions.live(false, Duration.ofMillis(100), 100))).error());
    }

    @Test
    void reportsInterruptionDuringBackoffAndFanOut() {
        gateway.otherwise(request -> ApiResponse.of(503, null, null));
        PolicyDefinition retrying = policy("call",
                apiCall("call").connection("api").method("GET").path("/a").retry(new RetryDefinition(2, List.of(50L)))
                        .fallback("done").build(),
                response("done", 200, null));

        try {
            Thread.currentThread().interrupt();
            ExecutionReport duringBackoff = run(retrying, Map.of(), LIVE);
            gateway.withLatency(Duration.ofMillis(500));
            Thread.currentThread().interrupt();
            ExecutionReport duringFanOut = run(fanOutCalls("GET"), Map.of("ids", List.of(1)), LIVE);

            assertEquals(ExecutionErrorCode.INTERRUPTED, failed(duringBackoff).error());
            assertEquals(ExecutionErrorCode.INTERRUPTED, failed(duringFanOut).error());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void validatesOptionsAndExposesDefaults() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.live(false, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.live(false, Duration.ofSeconds(1), 0));
        assertNotNull(executor.defaultIo());
        assertEquals(Duration.ZERO, RetryRule.NO_RETRY.backoffAfter(1));
        assertEquals("expression_error", ExecutionErrorCode.EXPRESSION_ERROR.code());
        assertEquals("x", new ExpressionEvaluationException("x").getMessage());
    }

    private PolicyDefinition fanOutCalls(String method) {
        return policy("fan",
                parallel("fan").over("input.ids").each("fetch").maxConcurrency(1).fallback("done").build(),
                apiCall("fetch").connection("api").method(method).path("/items/{id}").pathParams(Map.of("id", "item")).build(),
                response("done", 200, null));
    }

    private ExecutionReport run(PolicyDefinition definition, Map<String, Object> input, ExecutionOptions options) {
        return executor.execute(compiler.compile(definition), input, options);
    }

    private static ExecutionOutcome.Failed failed(ExecutionReport report) {
        return assertInstanceOf(ExecutionOutcome.Failed.class, report.outcome());
    }

    private static ExecutionOutcome.Responded responded(ExecutionReport report) {
        return assertInstanceOf(ExecutionOutcome.Responded.class, report.outcome());
    }
}
