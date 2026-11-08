package br.com.lucaslima.enginerules.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.lucaslima.enginerules.integration.support.ApiClient;
import br.com.lucaslima.enginerules.integration.support.ApiResponse;
import br.com.lucaslima.enginerules.integration.support.EngineRulesEnvironment;
import br.com.lucaslima.enginerules.integration.support.FakeGrpcProvider;
import br.com.lucaslima.enginerules.integration.support.FakeRestProvider;

abstract class IntegrationTest {

    static final String OWNER_GROUP = "credit-team";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    final EngineRulesEnvironment environment = EngineRulesEnvironment.shared();
    final ApiClient admin = environment.admin().as("ana", OWNER_GROUP);
    final ApiClient engine = environment.engine();
    final FakeRestProvider rest = environment.rest();
    final FakeGrpcProvider grpc = environment.grpc();

    static String uniqueName(String prefix) {
        return prefix + SEQUENCE.incrementAndGet() + "X" + Long.toString(System.nanoTime() % 1_000_000L);
    }

    ApiResponse createPolicy(String name, Map<String, Object> definition) {
        return createPolicy(name, definition, List.of());
    }

    ApiResponse createPolicy(String name, Map<String, Object> definition, List<String> allowedConsumers) {
        ApiResponse created = admin.post("/policies", Map.of("name", name, "ownerGroup", OWNER_GROUP,
                "allowedConsumers", allowedConsumers, "definition", definition));
        assertEquals(201, created.status(), () -> "creating " + name + ": " + created);
        return created;
    }

    ApiResponse publish(String name, String basedOn, Map<String, Object> definition) {
        return admin.post("/policies/" + name + "/versions", Map.of("basedOn", basedOn, "definition", definition));
    }

    String publishCandidate(String name, String basedOn, Map<String, Object> definition) {
        ApiResponse published = publish(name, basedOn, definition);
        assertEquals(201, published.status(), () -> "publishing " + name + ": " + published);
        return published.text("version");
    }

    ApiResponse promote(String name, String version) {
        return admin.put("/policies/" + name + "/stable-version", Map.of("version", version));
    }

    static ApiResponse assertStatus(int expected, ApiResponse response) {
        assertEquals(expected, response.status(), response::toString);
        return response;
    }

    static ApiResponse assertError(int status, String error, ApiResponse response) {
        assertStatus(status, response);
        assertEquals(error, response.error(), response::toString);
        return response;
    }

    ApiResponse execute(String name, Map<String, Object> input, String... headers) {
        return engine.post("/policies/" + name + "/executions", input, headers);
    }
}
