package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;

class PolicyQueriesIT extends IntegrationTest {

    @Test
    void findsAPolicyWithItsVersionHistory() {
        String name = uniqueName("History");
        createPolicy(name, ageGate(18));
        promote(name, publishCandidate(name, "1.0.0", ageGate(21)));
        publishCandidate(name, "1.0.1", ageGate(25));

        ApiResponse policy = assertStatus(200, admin.get("/policies/" + name));

        assertEquals(List.of("RETIRED", "STABLE", "PENDING_CANDIDATE"), ((List<?>) policy.at("versions")).stream()
                .map(version -> ((Map<?, ?>) version).get("status")).toList());
        assertEquals("1.0.2", policy.text("pendingCandidate"));
    }

    @Test
    void returnsTheDefinitionOfASpecificVersion() {
        String name = uniqueName("Definition");
        createPolicy(name, ageGate(18));

        ApiResponse version = assertStatus(200, admin.get("/policies/" + name + "/versions/1.0.0"));

        assertEquals("evaluate", version.text("definition.entry"));
        assertEquals("input.age >= 18", version.text("definition.states.0.map.adult"));
        assertEquals(64, version.text("hash").length());
        assertError(404, "version_not_found", admin.get("/policies/" + name + "/versions/1.2.3"));
        assertError(404, "policy_not_found", admin.get("/policies/" + uniqueName("Ghost")));
    }

    @Test
    void filtersByNamePrefixAndOwner() {
        String prefix = uniqueName("Prefix");
        createPolicy(prefix + "A", ageGate(18));
        createPolicy(prefix + "B", ageGate(18));

        ApiResponse byPrefix = assertStatus(200, admin.get("/policies?namePrefix=" + prefix));
        ApiResponse byOtherOwner = admin.get("/policies?namePrefix=" + prefix + "&owner=marketing");

        assertEquals(2L, byPrefix.number("totalItems"));
        assertEquals(List.of(prefix + "A", prefix + "B"), ((List<?>) byPrefix.at("items")).stream()
                .map(item -> ((Map<?, ?>) item).get("name")).sorted().toList());
        assertEquals(0L, byOtherOwner.number("totalItems"));
    }

    @Test
    void filtersPoliciesWithAPendingCandidate() {
        String prefix = uniqueName("Pending");
        createPolicy(prefix + "Idle", ageGate(18));
        createPolicy(prefix + "Busy", ageGate(18));
        publishCandidate(prefix + "Busy", "1.0.0", ageGate(21));

        ApiResponse pending = admin.get("/policies?namePrefix=" + prefix + "&pendingCandidate=true");
        ApiResponse idle = admin.get("/policies?namePrefix=" + prefix + "&pendingCandidate=false");

        assertEquals(1L, pending.number("totalItems"));
        assertEquals(prefix + "Busy", pending.text("items.0.name"));
        assertEquals(prefix + "Idle", idle.text("items.0.name"));
    }

    @Test
    void paginatesResults() {
        String prefix = uniqueName("Paged");
        for (String suffix : List.of("A", "B", "C")) {
            createPolicy(prefix + suffix, ageGate(18));
        }

        ApiResponse second = assertStatus(200, admin.get("/policies?namePrefix=" + prefix + "&pageNumber=2&pageSize=2"));

        assertEquals(3L, second.number("totalItems"));
        assertEquals(2L, second.number("pageNumber"));
        assertEquals(2L, second.number("pageSize"));
        assertEquals(1, ((List<?>) second.at("items")).size());
        assertTrue(admin.get("/policies?pageSize=101").status() == 400);
        assertTrue(admin.get("/policies?pageNumber=0").status() == 400);
    }
}
