package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.list;
import static br.com.lucaslima.enginerules.integration.support.Definitions.obj;
import static br.com.lucaslima.enginerules.integration.support.Definitions.policy;
import static br.com.lucaslima.enginerules.integration.support.Definitions.response;
import static br.com.lucaslima.enginerules.integration.support.Definitions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.integration.support.ApiResponse;
import br.com.lucaslima.enginerules.integration.support.FakeGrpcProvider;

class GrpcIT extends IntegrationTest {

    @Test
    void callsAUnaryRpcAndExposesTheReplyToThePolicy() {
        String name = uniqueName("GrpcScore");
        String document = name.toLowerCase().substring(0, 6);
        createPolicy(name, scorePolicy(Map.of()));

        ApiResponse scored = assertStatus(200, execute(name, Map.of("document", document)));

        assertEquals(600L, scored.number("data.score"));
        assertEquals("A", scored.text("data.band"));
        assertEquals("OK", scored.text("data.grpcStatus"));
        assertEquals(1, grpc.calls(document));
        assertEquals("acme", grpc.tenantSeenFor(document));
    }

    @Test
    void mapsGrpcStatusesToHttpEquivalentsForTheTransitions() {
        String name = uniqueName("GrpcStatus");
        createPolicy(name, scorePolicy(Map.of()));

        ApiResponse missing = assertStatus(404, execute(name, Map.of("document", "missing")));
        ApiResponse down = assertStatus(503, execute(name, Map.of("document", "down")));

        assertEquals("unknown", missing.text("state"));
        assertEquals("no score for missing", missing.text("data.reason"));
        assertEquals("unavailable", down.text("state"));
    }

    @Test
    void turnsAnExceededDeadlineIntoATimeout() {
        String name = uniqueName("GrpcDeadline");
        createPolicy(name, scorePolicy(Map.of("timeoutMs", 300)));

        assertEquals("timedOut", assertStatus(504, execute(name, Map.of("document", "slow-" + name))).text("state"));
    }

    @Test
    void verifiesTheContractWhenThePolicyIsCompiled() {
        Map<String, String> problems = Map.of(
                "connection", "unknown gRPC connection 'nowhere'",
                "rpc", "has no rpc 'GetRating'",
                "field", "has no field 'colour'",
                "streaming", "is streaming",
                "rest", "field 'path' is not allowed for protocol 'grpc'");
        Map<String, Map<String, Object>> overrides = Map.of(
                "connection", Map.of("connection", "nowhere"),
                "rpc", Map.of("rpc", "GetRating"),
                "field", Map.of("message", Map.of("colour", "'blue'")),
                "streaming", Map.of("rpc", "WatchScore"),
                "rest", Map.of("path", "/scores"));

        problems.forEach((problem, expected) -> {
            ApiResponse refused = admin.post("/policies", Map.of("name", uniqueName("GrpcContract"), "ownerGroup", OWNER_GROUP,
                    "definition", scorePolicy(overrides.get(problem))));
            assertError(422, "policy_compilation_failed", refused);
            assertTrue(refused.at("details").toString().contains(expected), () -> problem + ": " + refused);
        });
    }

    @Test
    void cachesReadOnlyRepliesAndRejectsCachingWrites() {
        String name = uniqueName("GrpcCache");
        String document = "cache" + name.toLowerCase();
        createPolicy(name, scorePolicy(Map.of("ttlSeconds", 60)));

        assertStatus(200, execute(name, Map.of("document", document)));
        assertStatus(200, execute(name, Map.of("document", document)));

        assertEquals(1, grpc.calls(document));
        ApiResponse refused = admin.post("/policies", Map.of("name", uniqueName("GrpcCacheWrite"), "ownerGroup", OWNER_GROUP,
                "definition", registerPolicy(Map.of("ttlSeconds", 60))));
        assertError(422, "policy_compilation_failed", refused);
        assertTrue(refused.at("details").toString().contains("ttlSeconds is only allowed for GET or readOnly calls"));
    }

    @Test
    void previewPerformsReadOnlyCallsButBlocksWrites() {
        String readDocument = "previewread" + uniqueName("x").toLowerCase();
        String writeDocument = "previewwrite" + uniqueName("x").toLowerCase();

        ApiResponse read = assertStatus(200, engine.post("/previews", Map.of("policy", scorePolicy(Map.of()),
                "input", Map.of("document", readDocument))));
        ApiResponse write = assertStatus(200, engine.post("/previews", Map.of("policy", registerPolicy(Map.of()),
                "input", Map.of("document", writeDocument))));

        assertEquals("responded", read.text("outcome.type"));
        assertEquals(List.of(), read.at("writeStates"));
        assertEquals(1, grpc.calls(readDocument));
        assertEquals("preview_blocked_write", write.text("outcome.type"));
        assertEquals(List.of("register"), write.at("writeStates"));
        assertEquals(0, grpc.calls(writeDocument));
    }

    @Test
    void executesWritesOutsideThePreview() {
        String name = uniqueName("GrpcWrite");
        String document = "write" + name.toLowerCase();
        createPolicy(name, registerPolicy(Map.of()));

        ApiResponse registered = assertStatus(201, execute(name, Map.of("document", document)));

        assertEquals(true, registered.at("data.accepted"));
        assertEquals(1, grpc.calls(document));
    }

    private static Map<String, Object> scorePolicy(Map<String, Object> overrides) {
        Map<String, Object> call = obj("id", "score", "kind", "apiCall", "protocol", "grpc", "connection", "bureau",
                "service", FakeGrpcProvider.SERVICE, "rpc", "GetScore", "message", obj("document", "input.document"),
                "readOnly", true, "timeoutMs", 1_000, "contextKey", "bureau",
                "transitions", list(when("result.timeout == true", "timedOut"), when("result.status == 404", "unknown"),
                        when("result.status >= 500", "unavailable")),
                "fallback", "scored");
        call.putAll(overrides);
        return policy("score", call,
                response("scored", 200, obj("score", "bureau.body.score", "band", "bureau.body.band",
                        "grpcStatus", "bureau.headers['grpc-status']")),
                response("unknown", 404, obj("reason", "bureau.headers['grpc-message']")),
                response("unavailable", 503, null),
                response("timedOut", 504, null));
    }

    private static Map<String, Object> registerPolicy(Map<String, Object> overrides) {
        Map<String, Object> call = obj("id", "register", "kind", "apiCall", "protocol", "grpc", "connection", "bureau",
                "service", FakeGrpcProvider.SERVICE, "rpc", "RegisterScore",
                "message", obj("document", "input.document", "score", "700"), "contextKey", "registration",
                "transitions", list(when("result.status != 200", "failed")), "fallback", "registered");
        call.putAll(overrides);
        return policy("register", call,
                response("registered", 201, obj("accepted", "registration.body.accepted")),
                response("failed", 502, null));
    }
}
