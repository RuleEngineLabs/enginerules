package br.com.lucaslima.enginerules.domain.execution;

import static br.com.lucaslima.enginerules.domain.fixtures.Policies.apiCall;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.policy;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.expression.BuiltinExpressionLanguage;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.definition.Transition;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.Protocol;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;
import br.com.lucaslima.enginerules.domain.execution.spi.ServiceContracts;
import br.com.lucaslima.enginerules.domain.fixtures.ScriptedApiGateway;

class GrpcCallTest {

    private static final ServiceContracts CONTRACTS = (connection, service, rpc, fields) ->
            "GetScore".equals(rpc) && fields.stream().allMatch("document"::equals)
                    ? Optional.empty() : Optional.of("unknown rpc " + rpc);

    private final PolicyCompiler compiler = new PolicyCompiler(new BuiltinExpressionLanguage(), CONTRACTS);
    private final ScriptedApiGateway gateway = new ScriptedApiGateway();
    private final PolicyExecutor executor = new PolicyExecutor(gateway, gateway);

    @Test
    void sendsDynamicMessagesAndRoutesOnTheMappedStatus() {
        gateway.thenRespond(ApiResponse.of(404, null, Map.of("grpc-status", "NOT_FOUND")));

        ExecutionReport report = executor.execute(compiler.compile(scoring(StateDefinition.builder("score", "apiCall"))),
                Map.of("document", "123"), ExecutionOptions.live(false, Duration.ofSeconds(2), 10));

        ApiRequest request = gateway.requests().get(0);
        assertEquals(Protocol.GRPC, request.protocol());
        assertEquals("scoring.v1.ScoreService", request.grpcService());
        assertEquals("GetScore", request.grpcRpc());
        assertEquals(Map.of("document", "123"), request.body());
        assertEquals("notFound", assertInstanceOf(ExecutionOutcome.Responded.class, report.outcome()).state());
    }

    @Test
    void readOnlyCallsRunInPreviewAndMayBeCached() {
        CompiledPolicy compiled = compiler.compile(scoring(StateDefinition.builder("score", "apiCall").readOnly(true).ttlSeconds(60)));

        executor.execute(compiled, Map.of("document", "1"), ExecutionOptions.preview(Duration.ofSeconds(2), 10));
        ExecutionReport second = executor.execute(compiled, Map.of("document", "1"), ExecutionOptions.preview(Duration.ofSeconds(2), 10));

        assertTrue(compiled.writeStates().isEmpty());
        assertEquals(1, gateway.requests().size());
        assertTrue(second.trace().get(0).resultFromCache());
    }

    @Test
    void nonReadOnlyCallsAreTreatedAsWrites() {
        CompiledPolicy compiled = compiler.compile(scoring(StateDefinition.builder("score", "apiCall")));

        ExecutionReport report = executor.execute(compiled, Map.of("document", "1"), ExecutionOptions.preview(Duration.ofSeconds(2), 10));

        assertEquals(List.of("score"), compiled.writeStates());
        assertInstanceOf(ExecutionOutcome.PreviewBlocked.class, report.outcome());
    }

    @Test
    void rejectsContractViolationsAndRestFields() {
        PolicyCompilationException unknownRpc = assertThrows(PolicyCompilationException.class, () -> compiler.compile(
                scoring(StateDefinition.builder("score", "apiCall").rpc("Delete"))));
        PolicyCompilationException restField = assertThrows(PolicyCompilationException.class, () -> compiler.compile(
                scoring(StateDefinition.builder("score", "apiCall").path("/x"))));
        PolicyCompilationException cacheOnWrite = assertThrows(PolicyCompilationException.class, () -> compiler.compile(
                scoring(StateDefinition.builder("score", "apiCall").ttlSeconds(30))));

        assertTrue(unknownRpc.violations().stream().anyMatch(v -> v.message().equals("unknown rpc Delete")));
        assertTrue(restField.violations().stream().anyMatch(v -> v.message().equals("field 'path' is not allowed for protocol 'grpc'")));
        assertTrue(cacheOnWrite.violations().stream().anyMatch(v -> v.message().startsWith("ttlSeconds is only allowed")));
    }

    @Test
    void validatesProtocolServiceRpcAndMessageShape() {
        assertViolation(StateDefinition.builder("score", "apiCall").protocol("soap"), "protocol must be 'rest' or 'grpc'");
        assertViolation(StateDefinition.builder("score", "apiCall").service("not a service"),
                "service must be a fully qualified gRPC service name");
        assertViolation(StateDefinition.builder("score", "apiCall").rpc("1bad"), "rpc must be a gRPC method name");
        assertViolation(StateDefinition.builder("score", "apiCall").message("input"), "message must be an object");
        PolicyCompilationException restWithGrpc = assertThrows(PolicyCompilationException.class, () -> compiler.compile(policy("call",
                apiCall("call").connection("api").method("GET").path("/x").service("a.B").fallback("done").build(),
                response("done", 200, null))));
        assertTrue(restWithGrpc.violations().stream().anyMatch(v -> v.message().equals("field 'service' is not allowed for protocol 'rest'")));
    }

    @Test
    void routesCallsToTheGatewayOfTheirProtocol() {
        ApiGateway rest = request -> ApiResponse.of(200, "rest", Map.of());
        Map<Protocol, ApiGateway> gateways = new EnumMap<>(Protocol.class);
        gateways.put(Protocol.REST, rest);
        ProtocolRoutingGateway routing = new ProtocolRoutingGateway(gateways);

        assertEquals("rest", routing.call(ApiRequest.rest("a", "GET", "/", Map.of(), Map.of(), null, Duration.ofSeconds(1))).body());
        assertEquals("no gateway for protocol grpc",
                routing.call(ApiRequest.grpc("a", "s.S", "M", null, Duration.ofSeconds(1))).error());
        assertEquals(Optional.empty(), ServiceContracts.unverified().verifyGrpcCall("a", "b", "c", Set.of()));
        assertEquals(Optional.empty(), ResultCache.disabled().find("x", Duration.ofSeconds(1)));
    }

    private void assertViolation(StateDefinition.Builder state, String message) {
        PolicyCompilationException failure = assertThrows(PolicyCompilationException.class, () -> compiler.compile(scoring(state)));
        assertTrue(failure.violations().stream().anyMatch(v -> v.message().equals(message)), () -> failure.violations().toString());
    }

    private static PolicyDefinition scoring(StateDefinition.Builder state) {
        StateDefinition defaults = state.build();
        StateDefinition.Builder completed = StateDefinition.builder("score", "apiCall")
                .protocol(defaults.protocol() == null ? "grpc" : defaults.protocol())
                .connection("bureau")
                .service(defaults.service() == null ? "scoring.v1.ScoreService" : defaults.service())
                .rpc(defaults.rpc() == null ? "GetScore" : defaults.rpc())
                .message(defaults.message() == null ? Map.of("document", "input.document") : defaults.message())
                .readOnly(defaults.readOnly())
                .ttlSeconds(defaults.ttlSeconds())
                .path(defaults.path())
                .contextKey("bureau")
                .transitions(List.of(new Transition("result.status == 404", "notFound")))
                .fallback("done");
        return policy("score", completed.build(), response("notFound", 404, null), response("done", 200, null));
    }
}
