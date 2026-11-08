package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static br.com.lucaslima.enginerules.integration.support.Definitions.list;
import static br.com.lucaslima.enginerules.integration.support.Definitions.obj;
import static br.com.lucaslima.enginerules.integration.support.Definitions.policy;
import static br.com.lucaslima.enginerules.integration.support.Definitions.response;
import static br.com.lucaslima.enginerules.integration.support.Definitions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;
import br.com.lucaslima.enginerules.integration.support.FakeRestProvider.Reply;

class PreviewsIT extends IntegrationTest {

    @Test
    void runsADraftAndReturnsTheOutcomeWithItsTrace() {
        ApiResponse preview = assertStatus(200, engine.post("/previews", Map.of("policy", ageGate(18), "input", Map.of("age", 40))));

        assertEquals("responded", preview.text("outcome.type"));
        assertEquals(200L, preview.number("outcome.status"));
        assertEquals("approved", preview.text("outcome.body.state"));
        assertEquals(List.of(), preview.at("writeStates"));
        assertEquals("evaluate", preview.text("trace.0.state"));
        assertEquals("approved", preview.text("trace.0.next"));
        assertEquals(2L, preview.number("steps"));
    }

    @Test
    void blocksWritesWithoutReachingTheProvider() {
        String path = "/registrations/" + uniqueName("preview").toLowerCase();
        rest.respond("POST", path, Reply.status(201, Map.of("id", "r-1")));
        Map<String, Object> definition = policy("register",
                obj("id", "register", "kind", "apiCall", "connection", "provider", "method", "POST", "path", path,
                        "body", obj("customer", "input.customerId"), "fallback", "done"),
                response("done", 201, null));

        ApiResponse preview = assertStatus(200, engine.post("/previews", Map.of("policy", definition, "input", Map.of("customerId", "c"))));

        assertEquals(List.of("register"), preview.at("writeStates"));
        assertEquals("preview_blocked_write", preview.text("outcome.type"));
        assertEquals("register", preview.text("outcome.body.state"));
        assertEquals(0, rest.calls("POST", path));
    }

    @Test
    void performsReadsAndTracesEveryAttempt() {
        String path = "/customers/" + uniqueName("preview").toLowerCase();
        rest.respond("GET", path, Reply.status(503, null), Reply.ok(Map.of("name", "Duda")));
        Map<String, Object> definition = policy("lookup",
                obj("id", "lookup", "kind", "apiCall", "connection", "provider", "method", "GET", "path", path,
                        "retry", obj("maxAttempts", 2, "backoffMs", list(5)), "contextKey", "customer",
                        "transitions", list(when("result.status == 200", "found")), "fallback", "missing"),
                response("found", 200, obj("name", "customer.body.name")),
                response("missing", 404, null));

        ApiResponse preview = assertStatus(200, engine.post("/previews", Map.of("policy", definition)));

        assertEquals("Duda", preview.text("outcome.body.data.name"));
        assertEquals(503L, preview.number("trace.0.attempts.0.status"));
        assertEquals(200L, preview.number("trace.0.attempts.1.status"));
        assertEquals(2, rest.calls("GET", path));
    }

    @Test
    void reportsExecutorFailuresInsideTheOutcome() {
        Map<String, Object> definition = policy("loop",
                obj("id", "loop", "kind", "execution", "map", obj("n", "input.n / 0"), "fallback", "done"),
                response("done", 200, null));

        ApiResponse preview = assertStatus(200, engine.post("/previews", Map.of("policy", definition, "input", Map.of("n", 1))));

        assertEquals("failed", preview.text("outcome.type"));
        assertEquals(500L, preview.number("outcome.status"));
        assertEquals("expression_error", preview.text("outcome.body.error"));
    }

    @Test
    void rejectsMalformedAndUncompilableDrafts() {
        assertError(400, "invalid_policy_document", engine.post("/previews", Map.of("policy", obj("entry", "x", "states", "nope"))));
        assertError(422, "policy_compilation_failed", engine.post("/previews", Map.of("policy",
                policy("start", obj("id", "start", "kind", "execution", "map", obj("x", "1"), "fallback", "ghost")))));
        assertError(400, "invalid_request", engine.post("/previews", Map.of("input", Map.of())));
        assertError(400, "invalid_request", engine.post("/previews", Map.of("policy", ageGate(18), "input", List.of(1))));
    }

    @Test
    void neverPublishesTheDraft() {
        String name = uniqueName("Draft");
        Map<String, Object> definition = ageGate(18);
        definition.put("id", name);

        ApiResponse preview = engine.post("/previews", Map.of("policy", definition, "input", Map.of("age", 1)));

        assertStatus(200, preview);
        assertEquals(403L, preview.number("outcome.status"));
        assertError(404, "policy_not_found", admin.get("/policies/" + name));
        assertTrue(preview.number("elapsedMicros") >= 0);
    }
}
