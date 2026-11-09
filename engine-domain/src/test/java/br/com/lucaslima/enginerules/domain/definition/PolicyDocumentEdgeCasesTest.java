package br.com.lucaslima.enginerules.domain.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.catalog.ChangeClassifier;
import br.com.lucaslima.enginerules.domain.catalog.VersionBump;

class PolicyDocumentEdgeCasesTest {

    @Test
    void readsAndWritesEveryApiCallField() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", "call");
        state.put("kind", "apiCall");
        state.put("connection", "bureau");
        state.put("method", "POST");
        state.put("path", "/scores/{id}");
        state.put("pathParams", Map.of("id", "input.id"));
        state.put("query", Map.of("full", "true"));
        state.put("body", Map.of("doc", "input.doc"));
        state.put("timeoutMs", 1500L);
        state.put("retry", Map.of("maxAttempts", 3, "backoffMs", List.of(10, 20.0)));
        state.put("readOnly", false);
        state.put("transitions", List.of(Map.of("when", "result.status == 200", "to", "done")));
        state.put("fallback", "done");
        Map<String, Object> tree = Map.of("entry", "call", "id", "Ignored", "version", "1.0.0",
                "states", List.of(state, Map.of("id", "done", "kind", "response", "status", BigInteger.valueOf(200))));

        PolicyDefinition definition = PolicyDocument.read(tree);

        StateDefinition call = definition.state("call").orElseThrow();
        assertEquals(new RetryDefinition(3, List.of(10L, 20L)), call.retry());
        assertEquals(1500, call.timeoutMs());
        assertEquals(Boolean.FALSE, call.readOnly());
        assertEquals(Optional.empty(), definition.state("missing"));
        assertEquals(definition, PolicyDocument.read(PolicyDocument.write(definition)));
    }

    @Test
    void collectsEveryShapeProblem() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("entry", 5);
        tree.put("states", Arrays.asList(
                "not-an-object",
                Map.of("id", "a", "kind", "apiCall", "transitions", "x", "retry", Map.of("maxAttempts", 1.5, "backoffMs", "x", "jitter", 1),
                        "map", Map.of("k", 1), "readOnly", "yes", "status", 3_000_000_000L, "maxConcurrency", "two"),
                Map.of("id", "b", "kind", "execution", "transitions", List.of("bad"), "retry", "x", "map", "x")));

        InvalidPolicyDocumentException failure = assertThrows(InvalidPolicyDocumentException.class, () -> PolicyDocument.read(tree));

        List<String> problems = failure.problems();
        assertTrue(problems.contains("policy.entry: expected a string"));
        assertTrue(problems.contains("states[0]: expected an object"));
        assertTrue(problems.contains("state 'a'.transitions: expected an array"));
        assertTrue(problems.contains("state 'a'.retry.maxAttempts: expected an integer"));
        assertTrue(problems.contains("state 'a'.retry.backoffMs: expected an array"));
        assertTrue(problems.contains("state 'a'.retry: unknown field 'jitter'"));
        assertTrue(problems.contains("state 'a'.map.k: expected a string"));
        assertTrue(problems.contains("state 'a'.readOnly: expected a boolean"));
        assertTrue(problems.contains("state 'a'.status: integer out of range"));
        assertTrue(problems.contains("state 'a'.maxConcurrency: expected an integer"));
        assertTrue(problems.contains("state 'b'.transitions[0]: expected an object"));
        assertTrue(problems.contains("state 'b'.retry: expected an object"));
        assertTrue(problems.contains("state 'b'.map: expected an object"));
        assertTrue(failure.getMessage().startsWith("invalid policy document"));
    }

    @Test
    void rejectsNonObjectPoliciesAndMissingStates() {
        assertTrue(assertThrows(InvalidPolicyDocumentException.class, () -> PolicyDocument.read("x")).problems()
                .contains("policy: expected an object"));
        assertTrue(assertThrows(InvalidPolicyDocumentException.class, () -> PolicyDocument.read(Map.of("entry", "a"))).problems()
                .contains("policy.states: expected an array"));
        assertTrue(assertThrows(InvalidPolicyDocumentException.class, () -> PolicyDocument.read(
                Map.of("entry", "a", "states", List.of(Map.of("kind", "response", "status", BigDecimal.valueOf(2.5)))))).problems()
                .contains("states[0].status: expected an integer"));
    }

    @Test
    void canonicalFormEscapesTextAndNormalisesValues() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("b", "quote\" slash\\ line\n return\r tab\t bell\u0007");
        tree.put("a", Arrays.asList(true, null, 1.50, Double.NaN, Float.POSITIVE_INFINITY, 0.0, new StringBuilder("raw")));

        String canonical = CanonicalForm.of(tree);

        assertEquals("{\"a\":[true,null,1.5,null,null,0,\"raw\"],\"b\":\"quote\\\" slash\\\\ line\\n return\\r tab\\t bell\\u0007\"}",
                canonical);
    }

    @Test
    void classifierToleratesDuplicateIdsInUncompiledDrafts() {
        PolicyDefinition duplicated = new PolicyDefinition("done", List.of(
                StateDefinition.builder("done", "response").status(200).build(),
                StateDefinition.builder("done", "response").status(201).build()));
        PolicyDefinition single = new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(200).build()));

        assertEquals(VersionBump.PATCH, new ChangeClassifier().classify(single, duplicated));
        assertEquals(List.of(), new StateDefinition("x", "execution", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null).transitionsOrEmpty());
    }
}
