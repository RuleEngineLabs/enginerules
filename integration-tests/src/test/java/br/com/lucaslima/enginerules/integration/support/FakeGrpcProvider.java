package br.com.lucaslima.enginerules.integration.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;

public final class FakeGrpcProvider implements AutoCloseable {

    public static final String SERVICE = "scoring.v1.ScoreService";

    private static final Metadata.Key<String> TENANT = Metadata.Key.of("x-tenant", Metadata.ASCII_STRING_MARSHALLER);

    private final Server server;
    private final Path descriptorSet;
    private final Map<String, AtomicInteger> callsByDocument = new ConcurrentHashMap<>();
    private final Map<String, String> tenantByDocument = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    private FakeGrpcProvider(Path descriptorSet, Descriptors.ServiceDescriptor service) throws IOException {
        this.descriptorSet = descriptorSet;
        this.server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
                .addService(ServerInterceptors.intercept(definition(service), tenantCapture()))
                .build()
                .start();
    }

    public static FakeGrpcProvider start() {
        try {
            FileDescriptorProto proto = scoringProto();
            Path file = Files.createTempFile("scoring", ".pb");
            Files.write(file, FileDescriptorSet.newBuilder().addFile(proto).build().toByteArray());
            Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[0]);
            return new FakeGrpcProvider(file, descriptor.findServiceByName("ScoreService"));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (Descriptors.DescriptorValidationException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    public String target() {
        return "localhost:" + server.getPort();
    }

    public Path descriptorSet() {
        return descriptorSet;
    }

    public int calls(String document) {
        AtomicInteger counter = callsByDocument.get(document);
        return counter == null ? 0 : counter.get();
    }

    public String tenantSeenFor(String document) {
        return tenantByDocument.get(document);
    }

    @Override
    public void close() {
        server.shutdownNow();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private ServerServiceDefinition definition(Descriptors.ServiceDescriptor service) {
        Descriptors.MethodDescriptor getScore = service.findMethodByName("GetScore");
        Descriptors.MethodDescriptor registerScore = service.findMethodByName("RegisterScore");
        return ServerServiceDefinition.builder(SERVICE)
                .addMethod(unary(getScore), ServerCalls.asyncUnaryCall((request, observer) -> {
                    String document = text(request, "document");
                    count(document);
                    switch (document) {
                        case "missing" -> observer.onError(Status.NOT_FOUND.withDescription("no score for " + document).asRuntimeException());
                        case "down" -> observer.onError(Status.UNAVAILABLE.withDescription("bureau offline").asRuntimeException());
                        default -> {
                            if (document.startsWith("slow")) {
                                sleep(1_500);
                            }
                            int score = document.length() * 100;
                            observer.onNext(DynamicMessage.newBuilder(getScore.getOutputType())
                                    .setField(getScore.getOutputType().findFieldByName("score"), score)
                                    .setField(getScore.getOutputType().findFieldByName("band"), score >= 500 ? "A" : "B")
                                    .build());
                            observer.onCompleted();
                        }
                    }
                }))
                .addMethod(unary(registerScore), ServerCalls.asyncUnaryCall((request, observer) -> {
                    count(text(request, "document"));
                    observer.onNext(DynamicMessage.newBuilder(registerScore.getOutputType())
                            .setField(registerScore.getOutputType().findFieldByName("accepted"), true)
                            .build());
                    observer.onCompleted();
                }))
                .build();
    }

    private void count(String document) {
        callsByDocument.computeIfAbsent(document, ignored -> new AtomicInteger()).incrementAndGet();
        String tenant = currentTenant.get();
        if (tenant != null) {
            tenantByDocument.put(document, tenant);
        }
    }

    private ServerInterceptor tenantCapture() {
        return new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                String tenant = headers.get(TENANT);
                ServerCall.Listener<Q> delegate = next.startCall(call, headers);
                return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
                    @Override
                    public void onHalfClose() {
                        currentTenant.set(tenant);
                        try {
                            super.onHalfClose();
                        } finally {
                            currentTenant.remove();
                        }
                    }
                };
            }
        };
    }

    private static MethodDescriptor<DynamicMessage, DynamicMessage> unary(Descriptors.MethodDescriptor method) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, method.getName()))
                .setRequestMarshaller(new Marshaller(method.getInputType()))
                .setResponseMarshaller(new Marshaller(method.getOutputType()))
                .build();
    }

    private static String text(DynamicMessage message, String field) {
        return (String) message.getField(message.getDescriptorForType().findFieldByName(field));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static FileDescriptorProto scoringProto() {
        return FileDescriptorProto.newBuilder()
                .setName("scoring.proto")
                .setPackage("scoring.v1")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder().setName("ScoreRequest")
                        .addField(field("document", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder().setName("ScoreReply")
                        .addField(field("score", 1, FieldDescriptorProto.Type.TYPE_INT32))
                        .addField(field("band", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder().setName("RegisterRequest")
                        .addField(field("document", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("score", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                .addMessageType(DescriptorProto.newBuilder().setName("RegisterReply")
                        .addField(field("accepted", 1, FieldDescriptorProto.Type.TYPE_BOOL)))
                .addService(ServiceDescriptorProto.newBuilder().setName("ScoreService")
                        .addMethod(method("GetScore", "ScoreRequest", "ScoreReply", false))
                        .addMethod(method("RegisterScore", "RegisterRequest", "RegisterReply", false))
                        .addMethod(method("WatchScore", "ScoreRequest", "ScoreReply", true)))
                .build();
    }

    private static MethodDescriptorProto.Builder method(String name, String input, String output, boolean streaming) {
        return MethodDescriptorProto.newBuilder().setName(name)
                .setInputType(".scoring.v1." + input).setOutputType(".scoring.v1." + output)
                .setServerStreaming(streaming);
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private record Marshaller(Descriptors.Descriptor type) implements MethodDescriptor.Marshaller<DynamicMessage> {

        @Override
        public InputStream stream(DynamicMessage value) {
            return value.toByteString().newInput();
        }

        @Override
        public DynamicMessage parse(InputStream stream) {
            try {
                return DynamicMessage.parseFrom(type, stream);
            } catch (IOException failure) {
                throw Status.INTERNAL.withDescription("cannot parse message").withCause(failure).asRuntimeException();
            }
        }
    }
}
