package br.com.lucaslima.enginerules.adapter.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCalls;

class GrpcAdapterEdgeCasesTest {

    private static final String SERVICE = "scoring.v1.ScoreService";

    @Test
    void opensPlaintextAndTlsChannelsWithoutConnecting() {
        Path descriptors = Path.of("unused.pb");
        ManagedChannel plaintext = GrpcChannels.open(new GrpcConnection("localhost:1", true, descriptors, null));
        ManagedChannel tls = GrpcChannels.open(new GrpcConnection("localhost:1", false, descriptors, Map.of()));

        assertFalse(plaintext.isShutdown());
        assertFalse(tls.isShutdown());
        plaintext.shutdownNow();
        tls.shutdownNow();
        assertThrows(NullPointerException.class, () -> new GrpcConnection("localhost:1", true, null, Map.of()));
        assertEquals(Map.of(), new GrpcConnection("localhost:1", true, descriptors, null).metadata());
    }

    @Test
    void resolvesDependenciesInAnyOrder() {
        FileDescriptorProto messages = FileDescriptorProto.newBuilder().setName("messages.proto").setPackage("scoring.v1")
                .setSyntax("proto3")
                .addMessageType(message("ScoreRequest", "document", FieldDescriptorProto.Type.TYPE_STRING))
                .addMessageType(message("ScoreReply", "score", FieldDescriptorProto.Type.TYPE_INT32))
                .build();
        FileDescriptorProto service = FileDescriptorProto.newBuilder().setName("service.proto").setPackage("scoring.v1")
                .setSyntax("proto3").addDependency("messages.proto")
                .addService(ServiceDescriptorProto.newBuilder().setName("ScoreService")
                        .addMethod(MethodDescriptorProto.newBuilder().setName("GetScore")
                                .setInputType(".scoring.v1.ScoreRequest").setOutputType(".scoring.v1.ScoreReply")))
                .build();

        GrpcDescriptors descriptors = GrpcDescriptors.from(FileDescriptorSet.newBuilder().addFile(service).addFile(messages).build());

        assertTrue(descriptors.method(SERVICE, "GetScore").isPresent());
        assertTrue(descriptors.method(SERVICE, "Missing").isEmpty());
        assertTrue(descriptors.service("scoring.v1.Other").isEmpty());
    }

    @Test
    void rejectsIncompleteOrInvalidDescriptorSets() {
        FileDescriptorProto orphan = FileDescriptorProto.newBuilder().setName("orphan.proto").addDependency("missing.proto").build();
        FileDescriptorProto invalid = FileDescriptorProto.newBuilder().setName("invalid.proto").setPackage("broken")
                .addMessageType(DescriptorProto.newBuilder().setName("Broken").addField(FieldDescriptorProto.newBuilder()
                        .setName("ghost").setNumber(1).setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".broken.Ghost")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .build();

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> GrpcDescriptors.from(FileDescriptorSet.newBuilder().addFile(orphan).build()));
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
                () -> GrpcDescriptors.from(FileDescriptorSet.newBuilder().addFile(invalid).build()));

        assertTrue(missing.getMessage().contains("--include_imports"));
        assertTrue(broken.getMessage().startsWith("invalid descriptor invalid.proto"));
        assertThrows(UncheckedIOException.class, () -> GrpcDescriptors.load(Path.of("does-not-exist.pb")));
    }

    @Test
    void marshalsDynamicMessagesBothWays() throws IOException {
        Descriptor reply = scoring().method(SERVICE, "GetScore").orElseThrow().getOutputType();
        DynamicMessageMarshaller marshaller = new DynamicMessageMarshaller(reply);
        DynamicMessage score = DynamicMessage.newBuilder(reply).setField(reply.findFieldByName("score"), 42).build();

        assertEquals(score, marshaller.parse(marshaller.stream(score)));
        assertThrows(UncheckedIOException.class, () -> marshaller.parse(new ByteArrayInputStream(new byte[] {(byte) 0xFF})));
    }

    @Test
    void callsWithoutMetadataOrBody() throws IOException {
        GrpcDescriptors descriptors = scoring();
        Path file = Files.createTempFile("scoring", ".pb");
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(echoService(descriptors)).build().start();
        try (GrpcApiGateway gateway = new GrpcApiGateway(Map.of("bureau", new GrpcConnection(name, true, file, Map.of())),
                Map.of("bureau", descriptors), ignored -> InProcessChannelBuilder.forName(name).directExecutor().build())) {

            ApiResponse response = gateway.call(ApiRequest.grpc("bureau", SERVICE, "GetScore", null, Duration.ofSeconds(2)));

            assertEquals(Integer.valueOf(200), response.status());
            assertEquals(Map.of(), response.body());
        } finally {
            server.shutdownNow();
        }
    }

    private static ServerServiceDefinition echoService(GrpcDescriptors descriptors) {
        com.google.protobuf.Descriptors.MethodDescriptor getScore = descriptors.method(SERVICE, "GetScore").orElseThrow();
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> method = io.grpc.MethodDescriptor
                .<DynamicMessage, DynamicMessage>newBuilder()
                .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE, "GetScore"))
                .setRequestMarshaller(new DynamicMessageMarshaller(getScore.getInputType()))
                .setResponseMarshaller(new DynamicMessageMarshaller(getScore.getOutputType()))
                .build();
        return ServerServiceDefinition.builder(SERVICE)
                .addMethod(method, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(DynamicMessage.newBuilder(getScore.getOutputType()).build());
                    observer.onCompleted();
                }))
                .build();
    }

    private static GrpcDescriptors scoring() {
        return GrpcDescriptors.from(FileDescriptorSet.newBuilder().addFile(GrpcApiGatewayTest.scoringProto()).build());
    }

    private static DescriptorProto.Builder message(String name, String field, FieldDescriptorProto.Type type) {
        return DescriptorProto.newBuilder().setName(name).addField(FieldDescriptorProto.newBuilder().setName(field).setNumber(1)
                .setType(type).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
    }
}
