package br.com.lucaslima.enginerules.adapter.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCalls;

class GrpcApiGatewayTest {

    private static final String SERVICE = "scoring.v1.ScoreService";

    private Server server;
    private GrpcApiGateway gateway;
    private GrpcDescriptors descriptors;

    @BeforeEach
    void startServer() throws IOException {
        FileDescriptorSet set = FileDescriptorSet.newBuilder().addFile(scoringProto()).build();
        Path file = Files.createTempFile("scoring", ".pb");
        Files.write(file, set.toByteArray());
        descriptors = GrpcDescriptors.load(file);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(scoringService()).build().start();
        GrpcConnection connection = new GrpcConnection(name, true, file, Map.of("x-tenant", "acme"));
        gateway = new GrpcApiGateway(Map.of("bureau", connection), Map.of("bureau", descriptors),
                ignored -> InProcessChannelBuilder.forName(name).directExecutor().build());
    }

    @AfterEach
    void stopServer() {
        gateway.close();
        server.shutdownNow();
    }

    @Test
    void callsUnaryRpcsWithDynamicMessages() {
        ApiResponse response = gateway.call(call("GetScore", Map.of("document", "123")));

        assertEquals(Integer.valueOf(200), response.status());
        assertEquals(Map.of("score", 900L), normalize(response.body()));
        assertEquals("OK", response.headers().get("grpc-status"));
    }

    @Test
    void mapsGrpcStatusesToHttpEquivalents() {
        ApiResponse missing = gateway.call(call("GetScore", Map.of("document", "missing")));
        ApiResponse down = gateway.call(call("GetScore", Map.of("document", "down")));

        assertEquals(Integer.valueOf(404), missing.status());
        assertEquals("NOT_FOUND", missing.headers().get("grpc-status"));
        assertEquals("no score for missing", missing.headers().get("grpc-message"));
        assertEquals(Integer.valueOf(503), down.status());
    }

    @Test
    void reportsDeadlinesAsTimeouts() {
        ApiResponse response = gateway.call(ApiRequest.grpc("bureau", SERVICE, "GetScore", Map.of("document", "slow"),
                Duration.ofMillis(50)));

        assertTrue(response.timeout());
    }

    @Test
    void rejectsUnknownConnectionsRpcsAndInvalidMessages() {
        assertTrue(gateway.call(ApiRequest.grpc("crm", SERVICE, "GetScore", Map.of(), Duration.ofSeconds(1))).error()
                .startsWith("unknown gRPC connection"));
        assertTrue(gateway.call(call("Missing", Map.of())).error().startsWith("unknown rpc"));
        assertTrue(gateway.call(call("GetScore", Map.of("colour", "blue"))).error().startsWith("invalid gRPC message"));
        assertTrue(gateway.call(ApiRequest.rest("bureau", "GET", "/x", Map.of(), Map.of(), null, Duration.ofSeconds(1)))
                .error().startsWith("the gRPC gateway only serves gRPC calls"));
    }

    @Test
    void verifiesContractsAtCompileTime() {
        GrpcServiceContracts contracts = new GrpcServiceContracts(Map.of("bureau", descriptors));

        assertEquals(Optional.empty(), contracts.verifyGrpcCall("bureau", SERVICE, "GetScore", Set.of("document")));
        assertTrue(contracts.verifyGrpcCall("crm", SERVICE, "GetScore", Set.of()).orElseThrow().startsWith("unknown gRPC connection"));
        assertTrue(contracts.verifyGrpcCall("bureau", "scoring.v1.Other", "GetScore", Set.of()).orElseThrow().contains("no service"));
        assertTrue(contracts.verifyGrpcCall("bureau", SERVICE, "Nope", Set.of()).orElseThrow().contains("no rpc"));
        assertTrue(contracts.verifyGrpcCall("bureau", SERVICE, "WatchScore", Set.of()).orElseThrow().contains("streaming"));
        assertTrue(contracts.verifyGrpcCall("bureau", SERVICE, "GetScore", Set.of("colour")).orElseThrow().contains("no field"));
    }

    @Test
    void mapsEveryStatusCode() {
        for (Status.Code code : Status.Code.values()) {
            int status = GrpcStatusMapping.httpEquivalent(code);
            assertTrue(status >= 200 && status < 600);
        }
    }

    private static ApiRequest call(String rpc, Map<String, Object> message) {
        return ApiRequest.grpc("bureau", SERVICE, rpc, message, Duration.ofSeconds(2));
    }

    private static Object normalize(Object body) {
        if (body instanceof Map<?, ?> map && map.get("score") instanceof Number number) {
            return Map.of("score", number.longValue());
        }
        return body;
    }

    private ServerServiceDefinition scoringService() {
        MethodDescriptor getScore = descriptors.method(SERVICE, "GetScore").orElseThrow();
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> method = io.grpc.MethodDescriptor
                .<DynamicMessage, DynamicMessage>newBuilder()
                .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE, "GetScore"))
                .setRequestMarshaller(new DynamicMessageMarshaller(getScore.getInputType()))
                .setResponseMarshaller(new DynamicMessageMarshaller(getScore.getOutputType()))
                .build();
        return ServerServiceDefinition.builder(SERVICE)
                .addMethod(method, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String document = (String) request.getField(getScore.getInputType().findFieldByName("document"));
                    switch (document) {
                        case "missing" -> observer.onError(Status.NOT_FOUND.withDescription("no score for missing").asRuntimeException());
                        case "down" -> observer.onError(Status.UNAVAILABLE.asRuntimeException());
                        case "slow" -> {
                            sleep(300);
                            observer.onNext(score(getScore, 1));
                            observer.onCompleted();
                        }
                        default -> {
                            observer.onNext(score(getScore, 900));
                            observer.onCompleted();
                        }
                    }
                }))
                .build();
    }

    private static DynamicMessage score(MethodDescriptor method, int value) {
        return DynamicMessage.newBuilder(method.getOutputType())
                .setField(method.getOutputType().findFieldByName("score"), value)
                .build();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static FileDescriptorProto scoringProto() {
        return FileDescriptorProto.newBuilder()
                .setName("scoring.proto")
                .setPackage("scoring.v1")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder().setName("ScoreRequest")
                        .addField(field("document", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder().setName("ScoreReply")
                        .addField(field("score", 1, FieldDescriptorProto.Type.TYPE_INT32)))
                .addService(ServiceDescriptorProto.newBuilder().setName("ScoreService")
                        .addMethod(MethodDescriptorProto.newBuilder().setName("GetScore")
                                .setInputType(".scoring.v1.ScoreRequest").setOutputType(".scoring.v1.ScoreReply"))
                        .addMethod(MethodDescriptorProto.newBuilder().setName("WatchScore")
                                .setInputType(".scoring.v1.ScoreRequest").setOutputType(".scoring.v1.ScoreReply")
                                .setServerStreaming(true)))
                .build();
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
}
