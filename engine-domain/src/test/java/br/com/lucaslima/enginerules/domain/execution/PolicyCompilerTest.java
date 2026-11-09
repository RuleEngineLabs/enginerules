package br.com.lucaslima.enginerules.domain.execution;

import static br.com.lucaslima.enginerules.domain.fixtures.Policies.apiCall;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.ageGate;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.execution;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.parallel;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.policy;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.response;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.RetryDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;

class PolicyCompilerTest {

    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage());

    @Test
    void compilesAValidPolicyIntoTypedSteps() {
        CompiledPolicy compiled = compiler.compile(ageGate());

        assertEquals("evaluate", compiled.entry());
        assertEquals(3, compiled.states().size());
        assertTrue(compiled.state("evaluate") instanceof CompiledState.ExecutionStep);
        assertTrue(compiled.writeStates().isEmpty());
    }

    @Test
    void rejectsAnEntryThatDoesNotExist() {
        assertViolation(policy("missing", response("done", 200, null)), "entry state 'missing' does not exist");
    }

    @Test
    void rejectsDuplicateStateIds() {
        assertViolation(policy("done", response("done", 200, null), response("done", 201, null)), "duplicate state id");
    }

    @Test
    void rejectsKindsOutsideTheCurrentPhase() {
        assertViolation(policy("query", StateDefinition.builder("query", "dbQuery").build()),
                "unsupported kind 'dbQuery' in the current phase");
    }

    @Test
    void requiresFallbackOnNonTerminalStates() {
        assertViolation(policy("evaluate", execution("evaluate").build(), response("done", 200, null)),
                "fallback is required for non-terminal states");
    }

    @Test
    void rejectsRoutingFieldsOnResponses() {
        StateDefinition invalid = StateDefinition.builder("done", "response").status(200).fallback("done").build();

        assertViolation(policy("done", invalid), "field 'fallback' is not allowed for kind 'response'");
    }

    @Test
    void rejectsReservedContextKeys() {
        assertViolation(policy("evaluate",
                execution("evaluate").map(Map.of("x", "1")).contextKey("input").fallback("done").build(),
                response("done", 200, null)), "contextKey 'input' is reserved");
    }

    @Test
    void rejectsReferencesToFieldsThatNoStateWrites() {
        assertViolation(policy("evaluate",
                execution("evaluate").map(Map.of("adult", "input.age >= 18")).contextKey("decision")
                        .transitions(when("decision.approved", "done")).fallback("done").build(),
                response("done", 200, null)), "field 'decision.approved' is never written by any state");
    }

    @Test
    void rejectsUnknownVariables() {
        assertViolation(policy("evaluate",
                execution("evaluate").transitions(when("customer.vip", "done")).fallback("done").build(),
                response("done", 200, null)), "unknown variable 'customer'");
    }

    @Test
    void enforcesParallelConcurrencyBounds() {
        assertViolation(policy("fanOut",
                parallel("fanOut").over("input.items").each("double").maxConcurrency(101).fallback("done").build(),
                execution("double").map(Map.of("value", "item * 2")).build(),
                response("done", 200, null)), "maxConcurrency is required and must be between 1 and 100");
    }

    @Test
    void forbidsRoutingOnStatesRunByEach() {
        assertViolation(policy("fanOut",
                parallel("fanOut").over("input.items").each("double").maxConcurrency(2).fallback("done").build(),
                execution("double").map(Map.of("value", "item * 2")).fallback("done").build(),
                response("done", 200, null)), "a state referenced by 'each' cannot declare 'fallback'");
    }

    @Test
    void capsRetryAttemptsAtThree() {
        assertViolation(policy("call",
                apiCall("call").connection("api").method("GET").path("/x")
                        .retry(new RetryDefinition(4, List.of(10L))).fallback("done").build(),
                response("done", 200, null)), "retry.maxAttempts must be between 1 and 3");
    }

    @Test
    void allowsResultCacheOnlyForGet() {
        assertViolation(policy("call",
                apiCall("call").connection("api").method("POST").path("/x").ttlSeconds(60).fallback("done").build(),
                response("done", 200, null)), "ttlSeconds is only allowed for GET or readOnly calls");
    }

    @Test
    void requiresEveryPathPlaceholderToBeBound() {
        assertViolation(policy("call",
                apiCall("call").connection("api").method("GET").path("/users/{id}").fallback("done").build(),
                response("done", 200, null)), "path placeholder '{id}' has no entry in pathParams");
    }

    @Test
    void listsWriteCallsSoPreviewCanWarnUpFront() {
        CompiledPolicy compiled = compiler.compile(policy("create",
                apiCall("create").connection("orders").method("POST").path("/orders").body(Map.of("id", "input.id"))
                        .fallback("done").build(),
                response("done", 201, null)));

        assertEquals(List.of("create"), compiled.writeStates());
    }

    @Test
    void reportsSyntaxErrorsWithTheirLocation() {
        PolicyCompilationException failure = assertThrows(PolicyCompilationException.class, () -> compiler.compile(
                policy("evaluate", execution("evaluate").transitions(when("input.age >=", "done")).fallback("done").build(),
                        response("done", 200, null))));

        assertTrue(failure.violations().get(0).location().startsWith("evaluate transitions[0].when"));
    }

    private void assertViolation(PolicyDefinition definition, String expectedMessage) {
        PolicyCompilationException failure = assertThrows(PolicyCompilationException.class, () -> compiler.compile(definition));
        assertTrue(failure.violations().stream().anyMatch(violation -> violation.message().equals(expectedMessage)),
                () -> "expected '" + expectedMessage + "' in " + failure.violations());
    }
}
