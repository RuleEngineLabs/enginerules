package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static br.com.lucaslima.enginerules.integration.support.Definitions.execution;
import static br.com.lucaslima.enginerules.integration.support.Definitions.list;
import static br.com.lucaslima.enginerules.integration.support.Definitions.obj;
import static br.com.lucaslima.enginerules.integration.support.Definitions.policy;
import static br.com.lucaslima.enginerules.integration.support.Definitions.response;
import static br.com.lucaslima.enginerules.integration.support.Definitions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;
import br.com.lucaslima.enginerules.integration.support.Eventually;

class CanariesIT extends IntegrationTest {

    @Test
    void startsACanaryForThePendingCandidateWithItsOwnCriteria() {
        String name = uniqueName("CanaryStart");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));

        ApiResponse started = assertStatus(201, startCanary(name, Map.of("candidate", candidate, "weight", 25,
                "durationMinutes", 90, "criteria", Map.of("minSamples", 50))));

        assertEquals("ACTIVE", started.text("status"));
        assertEquals("1.0.0", started.text("baseline"));
        assertEquals(25L, started.number("weight"));
        assertEquals(50L, started.number("criteria.minSamples"));
        assertEquals("PT1H30M", started.text("duration"));
        ApiResponse listed = assertStatus(200, admin.get("/policies/" + name + "/canaries"));
        assertEquals(started.text("id"), listed.text("0.id"));
    }

    @Test
    void routesEachConsumerStickilyBetweenStableAndCandidate() {
        String name = uniqueName("CanarySticky");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));
        startCanary(name, Map.of("candidate", candidate, "weight", 50));
        Map<String, String> routes = new HashMap<>();

        for (int consumer = 0; consumer < 40; consumer++) {
            ApiResponse first = execute(name, Map.of("age", 19), "X-Consumer-Id", "consumer-" + consumer);
            ApiResponse second = execute(name, Map.of("age", 19), "X-Consumer-Id", "consumer-" + consumer);
            String route = first.header("X-Policy-Route").orElseThrow();
            assertEquals(route, second.header("X-Policy-Route").orElseThrow());
            assertEquals("canary".equals(route) ? 403 : 200, first.status(), first::toString);
            assertEquals("canary".equals(route) ? candidate : "1.0.0", first.header("X-Policy-Version").orElseThrow());
            routes.put("consumer-" + consumer, route);
        }

        assertEquals(Set.of("stable", "canary"), new HashSet<>(routes.values()));
    }

    @Test
    void anExplicitVersionIgnoresTheCanary() {
        String name = uniqueName("CanaryPinned");
        createPolicy(name, ageGate(18));
        startCanary(name, Map.of("candidate", publishCandidate(name, "1.0.0", ageGate(21)), "weight", 99));

        for (int attempt = 0; attempt < 10; attempt++) {
            ApiResponse pinned = execute(name, Map.of("age", 19), "X-Policy-Version", "1.0.0", "X-Consumer-Id", "c" + attempt);
            assertEquals("pinned", pinned.header("X-Policy-Route").orElseThrow());
            assertStatus(200, pinned);
        }
    }

    @Test
    void refusesInvalidCanaries() {
        String name = uniqueName("CanaryInvalid");
        createPolicy(name, ageGate(18));
        Map<String, Object> majorChange = policy("evaluate",
                execution("evaluate", obj("adult", "input.age >= 18"), "decision", list(when("decision.adult", "approved")), "denied"),
                response("approved", 201, obj("approved", "true")),
                response("denied", 403, obj("approved", "false")));

        assertError(409, "candidate_not_pending", startCanary(name, Map.of("candidate", "1.0.0", "weight", 10)));
        String major = publishCandidate(name, "1.0.0", majorChange);
        assertError(409, "canary_major_blocked", startCanary(name, Map.of("candidate", major, "weight", 10)));
        admin.delete("/policies/" + name + "/versions/" + major);
        String patch = publishCandidate(name, "1.0.0", ageGate(21));
        assertError(400, "invalid_canary", startCanary(name, Map.of("candidate", patch, "weight", 100)));
        assertError(400, "invalid_canary", startCanary(name, Map.of("candidate", patch, "weight", 10, "durationMinutes", 2_000)));
        assertStatus(201, startCanary(name, Map.of("candidate", patch, "weight", 10)));
        assertError(409, "canary_active", startCanary(name, Map.of("candidate", patch, "weight", 10)));
        assertError(403, "not_owner", admin.as("bruno", "marketing").post("/policies/" + name + "/canaries",
                Map.of("candidate", patch, "weight", 10)));
    }

    @Test
    void cancellingOrEndingReturnsAllTrafficToStable() {
        String name = uniqueName("CanaryClose");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));
        startCanary(name, Map.of("candidate", candidate, "weight", 99));
        String consumer = routedConsumer(name);
        assertEquals("canary", execute(name, Map.of("age", 19), "X-Consumer-Id", consumer).header("X-Policy-Route").orElseThrow());

        ApiResponse cancelled = assertStatus(200, admin.delete("/policies/" + name + "/canaries/current"));
        assertEquals("CANCELLED", cancelled.text("status"));
        assertEquals("stable", execute(name, Map.of("age", 19), "X-Consumer-Id", consumer).header("X-Policy-Route").orElseThrow());
        assertError(404, "canary_not_found", admin.patch("/policies/" + name + "/canaries/current", Map.of("status", "ENDED")));

        startCanary(name, Map.of("candidate", candidate, "weight", 99));
        ApiResponse ended = assertStatus(200, admin.patch("/policies/" + name + "/canaries/current",
                Map.of("status", "ENDED", "reason", "enough data")));
        assertEquals("ENDED", ended.text("status"));
        assertEquals("enough data", ended.text("closingReason"));
        assertEquals(2, admin.get("/policies/" + name + "/canaries").list().size());
    }

    @Test
    void theEvaluatorAbortsACandidateThatKeepsFailing() {
        String name = uniqueName("CanaryAbort");
        createPolicy(name, ageGate(18));
        Map<String, Object> failing = policy("evaluate",
                execution("evaluate", obj("adult", "input.age / input.zero >= 18"), "decision", list(when("decision.adult", "approved")), "denied"),
                response("approved", 200, obj("approved", "true", "minimumAge", "'18'")),
                response("denied", 403, obj("approved", "false")));
        String candidate = publishCandidate(name, "1.0.0", failing);
        String canaryId = startCanary(name, Map.of("candidate", candidate, "weight", 99)).text("id");
        int canaryExecutions = 0;
        for (int attempt = 0; canaryExecutions < 110; attempt++) {
            ApiResponse executed = execute(name, Map.of("age", 30, "zero", 0));
            if ("canary".equals(executed.header("X-Policy-Route").orElseThrow())) {
                assertError(500, "expression_error", executed);
                canaryExecutions++;
            }
            assertTrue(attempt < 500, "the candidate should receive almost all traffic");
        }

        Map<?, ?> evaluation = Eventually.await(() -> evaluationOf(canaryId), found -> found.isPresent()
                && "aborted".equals(found.get().get("verdict")), "the canary to be aborted").orElseThrow();

        assertTrue(evaluation.get("reasons").toString().contains("early interruption"));
        assertEquals("CANCELLED", admin.get("/policies/" + name + "/canaries").text("0.status"));
        assertEquals("stable", execute(name, Map.of("age", 30, "zero", 0)).header("X-Policy-Route").orElseThrow());
        assertEquals("canary.aborted", admin.get("/audits?policy=" + name + "&action=canary.aborted").text("items.0.action"));
    }

    @Test
    void theEvaluatorKeepsObservingAHealthyCandidateBeforeTheDeadline() {
        String name = uniqueName("CanaryObserve");
        createPolicy(name, ageGate(18));
        String canaryId = startCanary(name, Map.of("candidate", publishCandidate(name, "1.0.0", ageGate(21)), "weight", 50))
                .text("id");
        for (int consumer = 0; consumer < 20; consumer++) {
            execute(name, Map.of("age", 30), "X-Consumer-Id", "healthy-" + consumer);
        }

        assertEquals("observing", evaluationOf(canaryId).orElseThrow().get("verdict"));
        assertError(409, "canary_not_inconclusive", admin.patch("/policies/" + name + "/canaries/current",
                Map.of("extendMinutes", 60)));
        assertError(400, "invalid_request", admin.patch("/policies/" + name + "/canaries/current", Map.of("status", "PAUSED")));
        assertEquals("ACTIVE", admin.get("/policies/" + name + "/canaries").text("0.status"));
    }

    private ApiResponse startCanary(String name, Map<String, Object> body) {
        return admin.post("/policies/" + name + "/canaries", body);
    }

    private String routedConsumer(String name) {
        for (int consumer = 0; ; consumer++) {
            String id = "routed-" + consumer;
            ApiResponse probe = execute(name, Map.of("age", 19), "X-Consumer-Id", id);
            if ("canary".equals(probe.header("X-Policy-Route").orElseThrow())) {
                return id;
            }
            assertTrue(consumer < 50, "a weight of 99 should route some consumer to the candidate");
        }
    }

    private Optional<Map<?, ?>> evaluationOf(String canaryId) {
        List<Object> evaluations = assertStatus(200, admin.post("/canary-evaluations", Map.of())).list();
        return evaluations.stream().<Map<?, ?>>map(item -> (Map<?, ?>) item)
                .filter(item -> canaryId.equals(item.get("canaryId")))
                .findFirst();
    }
}
