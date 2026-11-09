package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;

class VersionRemovalIT extends IntegrationTest {

    @Test
    void removesThePendingCandidateAndFreesTheSlot() {
        String name = uniqueName("RemoveCandidate");
        createPolicy(name, ageGate(18));
        publishCandidate(name, "1.0.0", ageGate(21));

        ApiResponse removed = admin.delete("/policies/" + name + "/versions/1.0.1");

        assertStatus(204, removed);
        ApiResponse version = admin.get("/policies/" + name + "/versions/1.0.1");
        assertEquals("REMOVED", version.text("status"));
        assertEquals(null, admin.get("/policies/" + name).at("pendingCandidate"));
        assertEquals("1.0.2", publishCandidate(name, "1.0.0", ageGate(25)));
    }

    @Test
    void neverRemovesTheStableVersion() {
        String name = uniqueName("RemoveStable");
        createPolicy(name, ageGate(18));

        assertError(409, "version_is_stable", admin.delete("/policies/" + name + "/versions/1.0.0"));
        assertStatus(200, execute(name, Map.of("age", 20)));
    }

    @Test
    void reportsRemovedAndUnknownVersions() {
        String name = uniqueName("RemoveTwice");
        createPolicy(name, ageGate(18));
        publishCandidate(name, "1.0.0", ageGate(21));
        assertStatus(204, admin.delete("/policies/" + name + "/versions/1.0.1"));

        assertError(410, "version_removed", admin.delete("/policies/" + name + "/versions/1.0.1"));
        assertError(404, "version_not_found", admin.delete("/policies/" + name + "/versions/3.0.0"));
        assertError(404, "policy_not_found", admin.delete("/policies/" + uniqueName("Ghost") + "/versions/1.0.0"));
    }

    @Test
    void aRemovedVersionCanNoLongerBePinned() {
        String name = uniqueName("RemovePinned");
        createPolicy(name, ageGate(18));
        promote(name, publishCandidate(name, "1.0.0", ageGate(21)));
        assertStatus(200, execute(name, Map.of("age", 19), "X-Policy-Version", "1.0.0"));
        assertStatus(204, admin.delete("/policies/" + name + "/versions/1.0.0"));

        assertError(410, "version_removed", execute(name, Map.of("age", 19), "X-Policy-Version", "1.0.0"));
        assertError(410, "version_removed", engine.post("/policies/" + name + "/executions?version=1.0.0", Map.of("age", 19)));
    }

    @Test
    void refusesToRemoveTheVersionUnderCanary() {
        String name = uniqueName("RemoveCanary");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));
        assertStatus(201, admin.post("/policies/" + name + "/canaries", Map.of("candidate", candidate, "weight", 10)));

        assertError(409, "version_in_use", admin.delete("/policies/" + name + "/versions/" + candidate));
    }

    @Test
    void operatorsMayRemoveButOutsidersMayNot() {
        String name = uniqueName("RemoveOperator");
        createPolicy(name, ageGate(18));
        publishCandidate(name, "1.0.0", ageGate(21));

        assertError(403, "not_owner", admin.as("bruno", "marketing").delete("/policies/" + name + "/versions/1.0.1"));
        assertStatus(204, admin.as("olga", "policy-operators").delete("/policies/" + name + "/versions/1.0.1"));
        assertNotNull(admin.get("/policies/" + name + "/versions/1.0.1").at("hash"));
    }
}
