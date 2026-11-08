package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static br.com.lucaslima.enginerules.integration.support.Definitions.execution;
import static br.com.lucaslima.enginerules.integration.support.Definitions.list;
import static br.com.lucaslima.enginerules.integration.support.Definitions.obj;
import static br.com.lucaslima.enginerules.integration.support.Definitions.policy;
import static br.com.lucaslima.enginerules.integration.support.Definitions.response;
import static br.com.lucaslima.enginerules.integration.support.Definitions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;
import br.com.lucaslima.enginerules.integration.support.Eventually;
import br.com.lucaslima.enginerules.integration.support.FakeRestProvider.Reply;

class ShadowsIT extends IntegrationTest {

    @Test
    void countsEquivalentOutputsAsEqual() {
        String name = uniqueName("ShadowEqual");
        createPolicy(name, ageGate(18));
        String target = publishCandidate(name, "1.0.0", gate("input.age > 17", "'18'"));
        String shadow = startShadow(name, Map.of("target", target, "percentage", 100)).text("id");

        executeTimes(name, 8, Map.of("age", 30));

        ApiResponse report = awaitComparisons(name, shadow, 8);
        assertEquals(8L, report.number("counts.EQUAL"));
        assertEquals(1.0, report.decimal("equalityRate"));
        assertEquals("ACTIVE", report.text("shadow.status"));
    }

    @Test
    void classifiesDivergentOutcomesAndData() {
        String name = uniqueName("ShadowDivergent");
        createPolicy(name, ageGate(18));
        String target = publishCandidate(name, "1.0.0", gate("input.age >= 21", "'21'"));
        String shadow = startShadow(name, Map.of("target", target, "percentage", 100)).text("id");

        executeTimes(name, 3, Map.of("age", 19));
        executeTimes(name, 2, Map.of("age", 40));

        ApiResponse report = awaitComparisons(name, shadow, 5);
        assertEquals(3L, report.number("counts.DIVERGENT_OUTCOME"));
        assertEquals(2L, report.number("counts.DIVERGENT_DATA"));
        assertEquals(0L, report.number("counts.EQUAL"));
    }

    @Test
    void ignoresTheConfiguredPaths() {
        String name = uniqueName("ShadowIgnored");
        createPolicy(name, ageGate(18));
        String target = publishCandidate(name, "1.0.0", gate("input.age >= 18", "'99'"));
        String shadow = startShadow(name, Map.of("target", target, "percentage", 100, "ignoredPaths", List.of("data.minimumAge")))
                .text("id");

        executeTimes(name, 4, Map.of("age", 50));

        assertEquals(4L, awaitComparisons(name, shadow, 4).number("counts.EQUAL"));
    }

    @Test
    void replaysTheRecordedProviderResponsesInsteadOfCallingAgain() {
        String name = uniqueName("ShadowReplay");
        String path = "/customers/" + name.toLowerCase();
        rest.respond("GET", path, Reply.ok(Map.of("tier", "gold")));
        createPolicy(name, tierPolicy(path, "'premium'"));
        String target = publishCandidate(name, "1.0.0", tierPolicy(path, "'vip'"));
        String shadow = startShadow(name, Map.of("target", target, "percentage", 100)).text("id");

        executeTimes(name, 3, Map.of());

        ApiResponse report = awaitComparisons(name, shadow, 3);
        assertEquals(3L, report.number("counts.DIVERGENT_DATA"));
        assertEquals(3, rest.calls("GET", path));
    }

    @Test
    void skipsNoCacheAndPinnedExecutions() {
        String name = uniqueName("ShadowSkip");
        createPolicy(name, ageGate(18));
        String target = publishCandidate(name, "1.0.0", gate("input.age > 17", "'18'"));
        String shadow = startShadow(name, Map.of("target", target, "percentage", 100)).text("id");

        engine.post("/policies/" + name + "/executions?noCache=true", Map.of("age", 30));
        execute(name, Map.of("age", 30), "X-Policy-Version", "1.0.0");
        execute(name, Map.of("age", 30));

        ApiResponse report = awaitComparisons(name, shadow, 1);
        assertEquals(1L, total(report));
    }

    @Test
    void pausesResumesAndEnds() {
        String name = uniqueName("ShadowLifecycle");
        createPolicy(name, ageGate(18));
        String target = publishCandidate(name, "1.0.0", gate("input.age > 17", "'18'"));
        String shadow = startShadow(name, Map.of("target", target, "percentage", 100)).text("id");
        String path = "/policies/" + name + "/shadows/" + shadow;

        assertEquals("PAUSED", assertStatus(200, admin.patch(path, Map.of("status", "PAUSED"))).text("status"));
        executeTimes(name, 3, Map.of("age", 30));
        assertEquals("ACTIVE", assertStatus(200, admin.patch(path, Map.of("status", "ACTIVE"))).text("status"));
        executeTimes(name, 2, Map.of("age", 30));
        ApiResponse report = awaitComparisons(name, shadow, 2);
        assertEquals("ENDED", assertStatus(200, admin.patch(path, Map.of("status", "ENDED"))).text("status"));
        executeTimes(name, 2, Map.of("age", 30));

        assertEquals(2L, total(report));
        assertEquals(2L, total(admin.get(path + "/report")));
        assertError(409, "shadow_not_live", admin.patch(path, Map.of("status", "ACTIVE")));
        assertStatus(201, startShadow(name, Map.of("target", target, "percentage", 50)));
        assertEquals(2, admin.get("/policies/" + name + "/shadows").list().size());
    }

    @Test
    void refusesInvalidShadows() {
        String name = uniqueName("ShadowInvalid");
        createPolicy(name, ageGate(18));
        String target = publishCandidate(name, "1.0.0", gate("input.age > 17", "'18'"));

        assertError(400, "invalid_shadow", startShadow(name, Map.of("target", "1.0.0", "percentage", 100)));
        assertError(400, "invalid_shadow", startShadow(name, Map.of("target", target, "percentage", 0)));
        assertError(400, "invalid_shadow", startShadow(name, Map.of("target", "4.0.0", "percentage", 10)));
        String shadow = assertStatus(201, startShadow(name, Map.of("target", target, "percentage", 10))).text("id");
        assertError(409, "shadow_active", startShadow(name, Map.of("target", target, "percentage", 10)));
        assertError(400, "invalid_request", admin.patch("/policies/" + name + "/shadows/" + shadow, Map.of("status", "RUNNING")));
        assertError(404, "shadow_not_found", admin.patch("/policies/" + name + "/shadows/ghost", Map.of("status", "PAUSED")));
        assertError(404, "shadow_not_found", admin.get("/policies/" + name + "/shadows/ghost/report"));
    }

    private ApiResponse startShadow(String name, Map<String, Object> body) {
        return admin.post("/policies/" + name + "/shadows", body);
    }

    private void executeTimes(String name, int times, Map<String, Object> input) {
        for (int attempt = 0; attempt < times; attempt++) {
            execute(name, input);
        }
    }

    private ApiResponse awaitComparisons(String name, String shadow, long expected) {
        return Eventually.await(() -> admin.get("/policies/" + name + "/shadows/" + shadow + "/report"),
                report -> report.status() == 200 && total(report) >= expected, expected + " shadow comparisons");
    }

    private static long total(ApiResponse report) {
        return ((Map<?, ?>) report.at("counts")).values().stream().mapToLong(value -> ((Number) value).longValue()).sum();
    }

    private static Map<String, Object> gate(String condition, String minimumAge) {
        return policy("evaluate",
                execution("evaluate", obj("adult", condition), "decision", list(when("decision.adult", "approved")), "denied"),
                response("approved", 200, obj("approved", "true", "minimumAge", minimumAge)),
                response("denied", 403, obj("approved", "false")));
    }

    private static Map<String, Object> tierPolicy(String path, String label) {
        return policy("lookup",
                obj("id", "lookup", "kind", "apiCall", "connection", "provider", "method", "GET", "path", path,
                        "contextKey", "customer", "transitions", list(when("result.status != 200", "failed")), "fallback", "found"),
                response("found", 200, obj("tier", "customer.body.tier", "label", label)),
                response("failed", 502, null));
    }
}
