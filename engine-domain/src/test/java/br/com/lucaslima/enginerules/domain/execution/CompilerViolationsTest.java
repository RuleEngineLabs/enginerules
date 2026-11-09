package br.com.lucaslima.enginerules.domain.execution;

import static br.com.lucaslima.enginerules.domain.fixtures.Policies.apiCall;
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

class CompilerViolationsTest {

    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage());

    @Test
    void reportsStructuralProblems() {
        assertViolation(new PolicyDefinition("a", List.of()), "at least one state is required");
        assertViolation(new PolicyDefinition(null, List.of(StateDefinition.builder(" ", "response").status(200).build())),
                "every state needs a non-blank id");
        assertViolation(new PolicyDefinition(" ", List.of(response("done", 200, null))), "entry is required");
    }

    @Test
    void reportsRoutingProblems() {
        assertViolation(policy("a", execution("a").fallback("ghost").build()), "fallback 'ghost' does not exist");
        assertViolation(policy("a", execution("a").contextKey(" ").fallback("done").build(), response("done", 200, null)),
                "contextKey cannot be blank");
        assertViolation(policy("fan",
                parallel("fan").over("input.items").each("child").maxConcurrency(2).transitions(when("true", "child")).fallback("done").build(),
                execution("child").map(Map.of("x", "item")).build(),
                response("done", 200, null)), "transition target 'child' is reserved for parallel 'each' and cannot be routed to");
    }

    @Test
    void reportsEachTargetProblems() {
        assertViolation(policy("fan",
                parallel("fan").over("input.items").each("fan").maxConcurrency(2).fallback("done").build(),
                response("done", 200, null)), "a state referenced by 'each' must be of kind execution or apiCall");
        assertViolation(policy("child",
                parallel("fan").over("input.items").each("child").maxConcurrency(2).fallback("done").build(),
                execution("child").map(Map.of("x", "item")).build(),
                response("done", 200, null)), "a state referenced by 'each' cannot be the entry");
        assertViolation(policy("fan",
                parallel("fan").over(" ").each("ghost").maxConcurrency(2).fallback("done").build(),
                response("done", 200, null)), "each 'ghost' does not exist");
        assertViolation(policy("fan",
                parallel("fan").each("ghost").maxConcurrency(2).fallback("done").build(),
                response("done", 200, null)), "over is required");
    }

    @Test
    void reportsRestCallProblems() {
        assertViolation(call(apiCall("call").method("TRACE").path("/x")), "connection is required");
        assertViolation(call(apiCall("call").connection("api").method("TRACE").path("/x")), "method must be one of");
        assertViolation(call(apiCall("call").connection("api").method("GET").path("/x").body(Map.of())), "GET calls cannot declare a body");
        assertViolation(call(apiCall("call").connection("api").method("GET").path("x")), "path is required and must start with '/'");
        assertViolation(call(apiCall("call").connection("api").method("GET").path("/x").pathParams(Map.of("id", "input.id"))),
                "pathParams 'id' is not used in path");
        assertViolation(call(apiCall("call").connection("api").method("GET").path("/x").timeoutMs(0)), "timeoutMs must be between 1 and 60000");
        assertViolation(call(apiCall("call").connection("api").method("GET").path("/x").retry(new RetryDefinition(null, List.of(-1L)))),
                "retry.backoffMs cannot contain negative values");
        assertViolation(call(apiCall("call").connection("api").method("GET").path("/x").ttlSeconds(5000)),
                "ttlSeconds must be between 1 and 1800");
    }

    @Test
    void reportsExpressionProblems() {
        assertViolation(policy("a", execution("a").map(Map.of("x", " ")).fallback("done").build(), response("done", 200, null)),
                "expression cannot be blank");
        assertViolation(call(apiCall("call").connection("api").method("GET").path("/x")
                .transitions(when("result.colour == 1", "done"))), "'result' has no field 'colour'");
        assertViolation(policy("fan",
                parallel("fan").over("input.items").each("child").maxConcurrency(2).contextKey("results")
                        .transitions(when("results.size > 0", "done")).fallback("done").build(),
                execution("child").map(Map.of("x", "item")).build(),
                response("done", 200, null)), "'results' is a list and has no field 'size'");
        assertViolation(policy("a",
                execution("a").map(Map.of("x", "1")).contextKey("shared").fallback("fan").build(),
                parallel("fan").over("input.items").each("child").maxConcurrency(2).contextKey("shared").fallback("done").build(),
                execution("child").map(Map.of("x", "item")).build(),
                response("done", 200, null)), "contextKey 'shared' is written both as a list and as an object");
        assertViolation(policy("a", response("a", 99, null)), "status is required and must be a valid HTTP status");
    }

    @Test
    void compilesDataTemplatesWithListsAndLiterals() {
        CompiledPolicy compiled = compiler.compile(policy("done",
                response("done", 200, Map.of("items", List.of("1 + 1", 7, true), "nested", Map.of("x", "input.x")))));

        Object rendered = ((CompiledState.ResponseStep) compiled.state("done")).data().orElseThrow()
                .render(Map.of("input", Map.of("x", "y")));
        assertEquals(Map.of("items", List.of(2L, 7, true), "nested", Map.of("x", "y")), rendered);
        assertThrows(IllegalStateException.class, () -> compiled.state("ghost"));
        assertTrue(new CompilationViolation("a", "b").toString().equals("a: b"));
    }

    private PolicyDefinition call(StateDefinition.Builder state) {
        return policy("call", state.fallback("done").build(), response("done", 200, null));
    }

    private void assertViolation(PolicyDefinition definition, String expected) {
        PolicyCompilationException failure = assertThrows(PolicyCompilationException.class, () -> compiler.compile(definition));
        assertTrue(failure.violations().stream().anyMatch(violation -> violation.message().startsWith(expected)),
                () -> "expected '" + expected + "' in " + failure.violations());
    }
}
