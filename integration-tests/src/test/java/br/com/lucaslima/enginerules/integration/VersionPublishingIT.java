package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static br.com.lucaslima.enginerules.integration.support.Definitions.execution;
import static br.com.lucaslima.enginerules.integration.support.Definitions.list;
import static br.com.lucaslima.enginerules.integration.support.Definitions.obj;
import static br.com.lucaslima.enginerules.integration.support.Definitions.policy;
import static br.com.lucaslima.enginerules.integration.support.Definitions.response;
import static br.com.lucaslima.enginerules.integration.support.Definitions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;

class VersionPublishingIT extends IntegrationTest {

    @Test
    void anExpressionChangeBecomesAPatchCandidateWithoutTouchingStable() {
        String name = uniqueName("Patch");
        createPolicy(name, ageGate(18));

        ApiResponse published = assertStatus(201, publish(name, "1.0.0", ageGate(21)));

        assertEquals("1.0.1", published.text("version"));
        assertEquals(Boolean.TRUE, published.at("created"));
        ApiResponse policy = admin.get("/policies/" + name);
        assertEquals("1.0.0", policy.text("stableVersion"));
        assertEquals("1.0.1", policy.text("pendingCandidate"));
        assertEquals("PENDING_CANDIDATE", admin.get("/policies/" + name + "/versions/1.0.1").text("status"));
        ApiResponse executed = execute(name, Map.of("age", 19));
        assertStatus(200, executed);
        assertEquals("1.0.0", executed.header("X-Policy-Version").orElseThrow());
    }

    @Test
    void aTopologyChangeBecomesAMinorCandidate() {
        String name = uniqueName("Minor");
        createPolicy(name, ageGate(18));
        Map<String, Object> withAudit = policy("audit",
                execution("audit", obj("seen", "true"), "audit", list(), "evaluate"),
                execution("evaluate", obj("adult", "input.age >= 18"), "decision", list(when("decision.adult", "approved")), "denied"),
                response("approved", 200, obj("approved", "true", "minimumAge", "'18'")),
                response("denied", 403, obj("approved", "false")));

        assertEquals("1.1.0", assertStatus(201, publish(name, "1.0.0", withAudit)).text("version"));
    }

    @Test
    void aResponseContractChangeBecomesAMajorCandidate() {
        String name = uniqueName("Major");
        createPolicy(name, ageGate(18));
        Map<String, Object> changedStatus = policy("evaluate",
                execution("evaluate", obj("adult", "input.age >= 18"), "decision", list(when("decision.adult", "approved")), "denied"),
                response("approved", 200, obj("approved", "true")),
                response("denied", 422, obj("approved", "false")));

        ApiResponse published = assertStatus(201, publish(name, "1.0.0", changedStatus));

        assertEquals("2.0.0", published.text("version"));
        ApiResponse audit = admin.get("/audits?policy=" + name + "&action=version.published");
        assertEquals("MAJOR", audit.text("items.0.details.bump"));
    }

    @Test
    void republishingKnownContentIsIdempotent() {
        String name = uniqueName("Idempotent");
        createPolicy(name, ageGate(18));

        ApiResponse sameAsStable = assertStatus(200, publish(name, "1.0.0", ageGate(18)));
        assertStatus(201, publish(name, "1.0.0", ageGate(21)));
        ApiResponse sameCandidate = assertStatus(200, publish(name, "1.0.0", ageGate(21)));

        assertEquals("1.0.0", sameAsStable.text("version"));
        assertEquals(Boolean.FALSE, sameAsStable.at("created"));
        assertEquals("1.0.1", sameCandidate.text("version"));
        assertEquals(Boolean.FALSE, sameCandidate.at("created"));
    }

    @Test
    void allowsASinglePendingCandidateAtATime() {
        String name = uniqueName("SinglePending");
        createPolicy(name, ageGate(18));
        publishCandidate(name, "1.0.0", ageGate(21));

        assertError(409, "pending_candidate_exists", publish(name, "1.0.0", ageGate(25)));
        assertError(409, "pending_candidate_exists", admin.as("carla", OWNER_GROUP)
                .post("/policies/" + name + "/versions", Map.of("basedOn", "1.0.0", "definition", ageGate(21))));
    }

    @Test
    void requiresTheStableVersionAsBase() {
        String name = uniqueName("BasedOn");
        createPolicy(name, ageGate(18));

        assertError(409, "based_on_not_stable", publish(name, "1.0.7", ageGate(21)));
        assertError(400, "invalid_request", admin.post("/policies/" + name + "/versions", Map.of("definition", ageGate(21))));
        assertError(400, "invalid_version", publish(name, "one", ageGate(21)));
    }

    @Test
    void onlyOwnersPublishAndOnlyValidDefinitionsArePublished() {
        String name = uniqueName("Guarded");
        createPolicy(name, ageGate(18));

        assertError(403, "not_owner", admin.as("bruno", "marketing")
                .post("/policies/" + name + "/versions", Map.of("basedOn", "1.0.0", "definition", ageGate(21))));
        assertError(422, "policy_compilation_failed", publish(name, "1.0.0",
                policy("done", obj("id", "done", "kind", "response"))));
        assertError(404, "policy_not_found", publish(uniqueName("Ghost"), "1.0.0", ageGate(21)));
        assertEquals(null, admin.get("/policies/" + name).at("pendingCandidate"));
    }

    @Test
    void thePendingCandidateIsNotExecutableByPinning() {
        String name = uniqueName("PinnedPending");
        createPolicy(name, ageGate(18));
        publishCandidate(name, "1.0.0", ageGate(21));

        assertError(409, "pending_candidate_not_executable", execute(name, Map.of("age", 30), "X-Policy-Version", "1.0.1"));
    }
}
