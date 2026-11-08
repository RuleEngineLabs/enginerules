package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static br.com.lucaslima.enginerules.integration.support.Definitions.obj;
import static br.com.lucaslima.enginerules.integration.support.Definitions.policy;
import static br.com.lucaslima.enginerules.integration.support.Definitions.respond;
import static br.com.lucaslima.enginerules.integration.support.Definitions.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;

class PoliciesIT extends IntegrationTest {

    @Test
    void createsAPolicyWhoseFirstVersionIsStableAndExecutable() {
        String name = uniqueName("AgeGate");

        ApiResponse created = createPolicy(name, ageGate(18));

        assertEquals("1.0.0", created.text("version"));
        assertEquals(Boolean.TRUE, created.at("created"));
        ApiResponse policy = assertStatus(200, admin.get("/policies/" + name));
        assertEquals("1.0.0", policy.text("stableVersion"));
        assertEquals(OWNER_GROUP, policy.text("ownerGroup"));
        assertEquals("STABLE", policy.text("versions.0.status"));
        assertEquals("ana", policy.text("versions.0.author"));
        assertStatus(200, execute(name, Map.of("age", 30)));
    }

    @Test
    void rejectsADuplicatedName() {
        String name = uniqueName("Duplicated");
        createPolicy(name, respond(200, null));

        ApiResponse duplicated = admin.post("/policies", Map.of("name", name, "ownerGroup", OWNER_GROUP,
                "definition", respond(201, null)));

        assertError(409, "policy_already_exists", duplicated);
    }

    @Test
    void rejectsNamesOutsideTheCamelCaseConvention() {
        for (String invalid : List.of("lowercase", "With-Dash", "A" + "b".repeat(40))) {
            ApiResponse response = admin.post("/policies", Map.of("name", invalid, "ownerGroup", OWNER_GROUP,
                    "definition", respond(200, null)));

            assertError(400, "invalid_policy_name", response);
        }
    }

    @Test
    void rejectsDefinitionsThatDoNotCompile() {
        String name = uniqueName("Broken");
        ApiResponse response = admin.post("/policies", Map.of("name", name, "ownerGroup", OWNER_GROUP,
                "definition", policy("start", obj("id", "start", "kind", "execution", "map", obj("x", "1 +"),
                        "fallback", "nowhere"))));

        assertError(422, "policy_compilation_failed", response);
        assertFalse(((List<?>) response.at("details")).isEmpty());
        assertError(404, "policy_not_found", admin.get("/policies/" + name));
    }

    @Test
    void rejectsMalformedDocuments() {
        ApiResponse unknownField = admin.post("/policies", Map.of("name", uniqueName("Malformed"), "ownerGroup", OWNER_GROUP,
                "definition", policy("done", obj("id", "done", "kind", "response", "status", 200, "colour", "blue"))));
        ApiResponse withoutStates = admin.post("/policies", Map.of("name", uniqueName("Malformed"), "ownerGroup", OWNER_GROUP,
                "definition", obj("entry", "done")));

        assertError(400, "invalid_policy_document", unknownField);
        assertError(400, "invalid_policy_document", withoutStates);
    }

    @Test
    void requiresAnIdentifiedActorFromTheOwnerGroup() {
        Map<String, Object> body = Map.of("name", uniqueName("Owned"), "ownerGroup", OWNER_GROUP,
                "definition", respond(200, null));

        assertError(400, "invalid_request", admin.anonymous().post("/policies", body));
        assertError(403, "not_owner", admin.as("bruno", "marketing").post("/policies", body));
    }

    @Test
    void rejectsRequestsWithoutTheRequiredFields() {
        assertError(400, "invalid_request", admin.post("/policies", Map.of("ownerGroup", OWNER_GROUP,
                "definition", respond(200, null))));
        assertError(400, "invalid_request", admin.post("/policies", Map.of("name", uniqueName("NoDefinition"),
                "ownerGroup", OWNER_GROUP)));
    }

    @Test
    void keepsTheAllowedConsumersOfThePolicy() {
        String name = uniqueName("Restricted");
        createPolicy(name, policy("done", response("done", 200, obj("ok", "true"))), List.of("checkout"));

        assertError(403, "consumer_not_allowed", execute(name, Map.of()));
        assertTrue(execute(name, Map.of(), "X-Consumer-Id", "checkout").status() == 200);
    }
}
