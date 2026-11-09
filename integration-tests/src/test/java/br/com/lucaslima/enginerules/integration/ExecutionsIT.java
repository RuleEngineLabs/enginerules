package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static br.com.lucaslima.enginerules.integration.support.Definitions.execution;
import static br.com.lucaslima.enginerules.integration.support.Definitions.list;
import static br.com.lucaslima.enginerules.integration.support.Definitions.obj;
import static br.com.lucaslima.enginerules.integration.support.Definitions.policy;
import static br.com.lucaslima.enginerules.integration.support.Definitions.response;
import static br.com.lucaslima.enginerules.integration.support.Definitions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;
import br.com.lucaslima.enginerules.integration.support.FakeRestProvider.Reply;

class ExecutionsIT extends IntegrationTest {

    @Test
    void respondsWithTheAuthoredStatusDataAndVersionHeaders() {
        String name = uniqueName("Respond");
        createPolicy(name, ageGate(18));

        ApiResponse approved = assertStatus(200, execute(name, Map.of("age", 30)));
        ApiResponse denied = assertStatus(403, execute(name, Map.of("age", 15)));

        assertEquals(Map.of("state", "approved", "data", Map.of("approved", true, "minimumAge", "18")), approved.json());
        assertEquals("denied", denied.text("state"));
        assertEquals("1.0.0", approved.header("X-Policy-Version").orElseThrow());
        assertEquals("stable", approved.header("X-Policy-Route").orElseThrow());
    }

    @Test
    void pinsAVersionThroughTheHeaderOrTheQueryParameter() {
        String name = uniqueName("Pinned");
        createPolicy(name, ageGate(18));
        promote(name, publishCandidate(name, "1.0.0", ageGate(21)));

        ApiResponse byHeader = execute(name, Map.of("age", 19), "X-Policy-Version", "1.0.0");
        ApiResponse byQuery = engine.post("/policies/" + name + "/executions?version=1.0.0", Map.of("age", 19));
        ApiResponse stable = execute(name, Map.of("age", 19));

        assertStatus(200, byHeader);
        assertEquals("pinned", byHeader.header("X-Policy-Route").orElseThrow());
        assertStatus(200, byQuery);
        assertEquals("1.0.0", byQuery.header("X-Policy-Version").orElseThrow());
        assertStatus(403, stable);
        assertEquals("1.0.1", stable.header("X-Policy-Version").orElseThrow());
    }

    @Test
    void reportsUnknownPoliciesVersionsAndConsumers() {
        String name = uniqueName("Unknowns");
        createPolicy(name, ageGate(18), java.util.List.of("checkout"));

        assertError(404, "policy_not_found", execute(uniqueName("Ghost"), Map.of()));
        assertError(404, "version_not_found", execute(name, Map.of(), "X-Policy-Version", "7.7.7", "X-Consumer-Id", "checkout"));
        assertError(404, "version_not_found", execute(name, Map.of(), "X-Policy-Version", "latest", "X-Consumer-Id", "checkout"));
        assertError(403, "consumer_not_allowed", execute(name, Map.of("age", 20), "X-Consumer-Id", "marketing"));
        assertError(400, "invalid_policy_name", execute("not-a-name", Map.of()));
    }

    @Test
    void callsRestProvidersWithPathQueryAndConnectionHeaders() {
        String name = uniqueName("Lookup");
        String customer = name.toLowerCase();
        rest.respond("GET", "/customers/" + customer, Reply.ok(Map.of("name", "Ana", "tier", "gold")));
        createPolicy(name, lookupPolicy(0, 0));

        ApiResponse found = assertStatus(200, execute(name, Map.of("customerId", customer, "segment", "retail")));

        assertEquals(Map.of("name", "Ana", "tier", "gold"), found.at("data"));
        assertEquals("segment=retail", rest.requests("/customers/" + customer).get(0).query());
        assertEquals("acme", rest.requests("/customers/" + customer).get(0).tenant());
    }

    @Test
    void routesProviderFailuresThroughTransitions() {
        String name = uniqueName("Failures");
        String missing = name.toLowerCase() + "missing";
        String broken = name.toLowerCase() + "broken";
        rest.respond("GET", "/customers/" + missing, Reply.status(404, Map.of("error", "nope")));
        rest.respond("GET", "/customers/" + broken, Reply.status(500, null));
        createPolicy(name, lookupPolicy(0, 0));

        assertEquals("unknown", assertStatus(404, execute(name, Map.of("customerId", missing, "segment", "x"))).text("state"));
        assertEquals("unavailable", assertStatus(503, execute(name, Map.of("customerId", broken, "segment", "x"))).text("state"));
    }

    @Test
    void retriesTransientFailures() {
        String name = uniqueName("Retry");
        String customer = name.toLowerCase();
        rest.respond("GET", "/customers/" + customer, Reply.status(503, null), Reply.status(503, null),
                Reply.ok(Map.of("name", "Bia", "tier", "silver")));
        createPolicy(name, lookupPolicy(3, 0));

        ApiResponse found = assertStatus(200, execute(name, Map.of("customerId", customer, "segment", "x")));

        assertEquals("Bia", found.text("data.name"));
        assertEquals(3, rest.calls("GET", "/customers/" + customer));
    }

    @Test
    void turnsSlowProvidersIntoTimeouts() {
        String name = uniqueName("Timeout");
        String customer = name.toLowerCase();
        rest.respond("GET", "/customers/" + customer, Reply.slow(1_500, Map.of("name", "late")));
        createPolicy(name, lookupPolicy(0, 0));

        ApiResponse timedOut = assertStatus(504, execute(name, Map.of("customerId", customer, "segment", "x")));

        assertEquals("timedOut", timedOut.text("state"));
    }

    @Test
    void cachesReadResultsPerRequestSignatureEvenWithNoCache() {
        String name = uniqueName("Cached");
        String customer = name.toLowerCase();
        String path = "/customers/" + customer;
        rest.respond("GET", path, Reply.ok(Map.of("name", "Caio", "tier", "gold")));
        createPolicy(name, lookupPolicy(0, 60));
        Map<String, Object> input = Map.of("customerId", customer, "segment", "x");

        assertStatus(200, execute(name, input));
        assertStatus(200, execute(name, input));
        assertEquals(1, rest.calls("GET", path));
        assertStatus(200, engine.post("/policies/" + name + "/executions?noCache=true", input));
        assertStatus(200, execute(name, input, "Cache-Control", "no-cache"));
        assertEquals(1, rest.calls("GET", path));
        assertStatus(200, execute(name, Map.of("customerId", customer, "segment", "other")));
        assertEquals(2, rest.calls("GET", path));
    }

    @Test
    void sendsWritesWithTheirBody() {
        String name = uniqueName("Write");
        String path = "/registrations/" + name.toLowerCase();
        rest.respond("POST", path, Reply.status(201, Map.of("id", "r-1")));
        createPolicy(name, policy("register",
                obj("id", "register", "kind", "apiCall", "connection", "provider", "method", "POST", "path", path,
                        "body", obj("customer", "input.customerId", "score", "input.score * 2"), "contextKey", "registration",
                        "transitions", list(when("result.status == 201", "registered")), "fallback", "failed"),
                response("registered", 201, obj("id", "registration.body.id")),
                response("failed", 502, null)));

        ApiResponse registered = assertStatus(201, execute(name, Map.of("customerId", "c-9", "score", 21)));

        assertEquals("r-1", registered.text("data.id"));
        Map<?, ?> sent = (Map<?, ?>) rest.requests(path).get(0).body();
        assertEquals("c-9", sent.get("customer"));
        assertEquals(42L, ((Number) sent.get("score")).longValue());
    }

    @Test
    void surfacesExecutorFailuresAsServerErrors() {
        String name = uniqueName("Failure");
        createPolicy(name, policy("divide",
                execution("divide", obj("ratio", "input.total / input.parts"), "math", list(), "done"),
                response("done", 200, obj("ratio", "math.ratio"))));

        ApiResponse failed = assertStatus(500, execute(name, Map.of("total", 10, "parts", 0)));

        assertEquals("expression_error", failed.error());
        assertNull(failed.at("state"));
        assertEquals(5L, assertStatus(200, execute(name, Map.of("total", 10, "parts", 2))).number("data.ratio"));
    }

    private static Map<String, Object> lookupPolicy(int maxAttempts, int ttlSeconds) {
        Map<String, Object> call = obj("id", "lookup", "kind", "apiCall", "connection", "provider", "method", "GET",
                "path", "/customers/{id}", "pathParams", obj("id", "input.customerId"), "query", obj("segment", "input.segment"),
                "timeoutMs", 300, "contextKey", "customer",
                "transitions", list(when("result.timeout == true", "timedOut"), when("result.status >= 500", "unavailable"),
                        when("result.status == 404", "unknown")),
                "fallback", "found");
        if (maxAttempts > 0) {
            call.put("retry", obj("maxAttempts", maxAttempts, "backoffMs", list(10, 20)));
        }
        if (ttlSeconds > 0) {
            call.put("ttlSeconds", ttlSeconds);
        }
        return policy("lookup", call,
                response("found", 200, obj("name", "customer.body.name", "tier", "customer.body.tier")),
                response("unknown", 404, obj("reason", "'unknown customer'")),
                response("unavailable", 503, null),
                response("timedOut", 504, null));
    }
}
