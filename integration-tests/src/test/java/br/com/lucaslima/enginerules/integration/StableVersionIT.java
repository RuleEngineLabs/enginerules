package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;

class StableVersionIT extends IntegrationTest {

    @Test
    void promotesThePendingCandidateAndTheEngineServesIt() {
        String name = uniqueName("Promote");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));

        ApiResponse move = assertStatus(200, promote(name, candidate));

        assertEquals("PROMOTION", move.text("kind"));
        assertEquals("1.0.0", move.text("from"));
        assertEquals("1.0.1", move.text("to"));
        ApiResponse executed = assertStatus(403, execute(name, Map.of("age", 19)));
        assertEquals("1.0.1", executed.header("X-Policy-Version").orElseThrow());
        assertEquals("stable", executed.header("X-Policy-Route").orElseThrow());
        assertEquals("RETIRED", admin.get("/policies/" + name + "/versions/1.0.0").text("status"));
    }

    @Test
    void rollsBackToAPreviousVersionDiscardingThePendingCandidate() {
        String name = uniqueName("Rollback");
        createPolicy(name, ageGate(18));
        promote(name, publishCandidate(name, "1.0.0", ageGate(21)));
        publishCandidate(name, "1.0.1", ageGate(30));

        ApiResponse move = assertStatus(200, promote(name, "1.0.0"));

        assertEquals("ROLLBACK", move.text("kind"));
        assertEquals("1.0.2", move.text("discardedCandidate"));
        ApiResponse policy = admin.get("/policies/" + name);
        assertEquals("1.0.0", policy.text("stableVersion"));
        assertEquals("1.0.1", policy.text("pendingCandidate"));
        assertEquals("REMOVED", admin.get("/policies/" + name + "/versions/1.0.2").text("status"));
        assertStatus(200, execute(name, Map.of("age", 19)));
    }

    @Test
    void refusesToMoveToTheCurrentStableOrToUnknownVersions() {
        String name = uniqueName("Refused");
        createPolicy(name, ageGate(18));

        assertError(409, "already_stable", promote(name, "1.0.0"));
        assertError(404, "version_not_found", promote(name, "9.9.9"));
        assertError(400, "invalid_version", promote(name, "latest"));
        assertError(404, "policy_not_found", promote(uniqueName("Ghost"), "1.0.0"));
    }

    @Test
    void refusesToMoveWhileACanaryIsOpen() {
        String name = uniqueName("CanaryLock");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));
        assertStatus(201, admin.post("/policies/" + name + "/canaries", Map.of("candidate", candidate, "weight", 10)));

        assertError(409, "canary_in_progress", promote(name, candidate));

        assertStatus(200, admin.delete("/policies/" + name + "/canaries/current"));
        assertStatus(200, promote(name, candidate));
    }

    @Test
    void onlyOwnersAndOperatorsMoveTheStableVersion() {
        String name = uniqueName("Operators");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));

        assertError(403, "not_owner", admin.as("bruno", "marketing")
                .put("/policies/" + name + "/stable-version", Map.of("version", candidate)));
        assertStatus(200, admin.as("olga", "policy-operators")
                .put("/policies/" + name + "/stable-version", Map.of("version", candidate)));
        assertError(400, "invalid_request", admin.put("/policies/" + name + "/stable-version", Map.of()));
    }
}
