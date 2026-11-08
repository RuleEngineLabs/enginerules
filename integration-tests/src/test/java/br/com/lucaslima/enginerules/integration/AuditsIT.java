package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;

class AuditsIT extends IntegrationTest {

    @Test
    void recordsTheWholeCatalogLifecycleNewestFirst() {
        String name = uniqueName("Lifecycle");
        createPolicy(name, ageGate(18));
        promote(name, publishCandidate(name, "1.0.0", ageGate(21)));
        publishCandidate(name, "1.0.1", ageGate(25));
        admin.delete("/policies/" + name + "/versions/1.0.2");

        ApiResponse audits = assertStatus(200, admin.get("/audits?policy=" + name));

        assertEquals(List.of("version.removed", "version.published", "stable.moved", "version.published", "policy.created"),
                actions(audits));
        assertEquals("1.0.0", audits.text("items.4.details.version"));
        assertEquals("PROMOTION", audits.text("items.2.details.kind"));
    }

    @Test
    void filtersByActor() {
        String name = uniqueName("ByActor");
        createPolicy(name, ageGate(18));
        admin.as("carla", OWNER_GROUP).post("/policies/" + name + "/versions", Map.of("basedOn", "1.0.0", "definition", ageGate(21)));

        ApiResponse byCarla = admin.get("/audits?policy=" + name + "&actor=carla");

        assertEquals(List.of("version.published"), actions(byCarla));
        assertEquals("carla", byCarla.text("items.0.actor"));
    }

    @Test
    void filtersByAction() {
        String name = uniqueName("ByAction");
        createPolicy(name, ageGate(18));
        promote(name, publishCandidate(name, "1.0.0", ageGate(21)));
        promote(name, "1.0.0");

        ApiResponse moves = admin.get("/audits?policy=" + name + "&action=stable.moved");

        assertEquals(2L, moves.number("totalItems"));
        assertEquals("ROLLBACK", moves.text("items.0.details.kind"));
        assertEquals("PROMOTION", moves.text("items.1.details.kind"));
    }

    @Test
    void paginatesTheTrail() {
        String name = uniqueName("AuditPages");
        createPolicy(name, ageGate(18));
        promote(name, publishCandidate(name, "1.0.0", ageGate(21)));

        ApiResponse last = admin.get("/audits?policy=" + name + "&pageNumber=2&pageSize=2");

        assertEquals(3L, last.number("totalItems"));
        assertEquals(List.of("policy.created"), actions(last));
    }

    @Test
    void recordsRolloutOperations() {
        String name = uniqueName("RolloutAudit");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));
        admin.post("/policies/" + name + "/canaries", Map.of("candidate", candidate, "weight", 10));
        admin.delete("/policies/" + name + "/canaries/current");

        ApiResponse audits = admin.get("/audits?policy=" + name);

        assertEquals(List.of("canary.cancelled", "canary.started"), actions(audits).subList(0, 2));
    }

    @Test
    void failedOperationsLeaveNoTrail() {
        String name = uniqueName("NoTrail");
        createPolicy(name, ageGate(18));
        admin.as("bruno", "marketing").post("/policies/" + name + "/versions", Map.of("basedOn", "1.0.0", "definition", ageGate(21)));
        promote(name, "1.0.0");

        assertEquals(List.of("policy.created"), actions(admin.get("/audits?policy=" + name)));
    }

    private static List<Object> actions(ApiResponse audits) {
        return ((List<?>) audits.at("items")).stream().map(item -> ((Map<?, ?>) item).get("action")).map(Object.class::cast).toList();
    }
}
