package br.com.lucaslima.enginerules.adapter.grpc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import br.com.lucaslima.enginerules.adapter.json.Json;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.Protocol;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;

public final class GrpcApiGateway implements ApiGateway, AutoCloseable {

    private final Map<String, GrpcConnection> connections;
    private final Map<String, GrpcDescriptors> descriptors;
    private final Function<GrpcConnection, ManagedChannel> channelFactory;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>> methods = new ConcurrentHashMap<>();

    public GrpcApiGateway(Map<String, GrpcConnection> connections, Map<String, GrpcDescriptors> descriptors,
                          Function<GrpcConnection, ManagedChannel> channelFactory) {
        this.connections = Map.copyOf(connections);
        this.descriptors = Map.copyOf(descriptors);
        this.channelFactory = channelFactory;
    }

    @Override
    public ApiResponse call(ApiRequest request) {
        if (request.protocol() != Protocol.GRPC) {
            return ApiResponse.failure("the gRPC gateway only serves gRPC calls");
        }
        GrpcConnection connection = connections.get(request.connection());
        GrpcDescriptors schema = descriptors.get(request.connection());
        if (connection == null || schema == null) {
            return ApiResponse.failure("unknown gRPC connection '" + request.connection() + "'");
        }
        Optional<MethodDescriptor> method = schema.method(request.grpcService(), request.grpcRpc());
        if (method.isEmpty()) {
            return ApiResponse.failure("unknown rpc " + request.grpcService() + "/" + request.grpcRpc());
        }
        try {
            DynamicMessage message = toMessage(method.get(), request.body());
            DynamicMessage reply = ClientCalls.blockingUnaryCall(channel(connection, request.connection()),
                    methodDescriptor(request, method.get()),
                    CallOptions.DEFAULT.withDeadlineAfter(request.timeout().toMillis(), TimeUnit.MILLISECONDS), message);
            return ApiResponse.of(200, toTree(reply), Map.of("grpc-status", Status.Code.OK.name()));
        } catch (StatusRuntimeException failure) {
            return fromStatus(failure.getStatus());
        } catch (InvalidProtocolBufferException invalid) {
            return ApiResponse.failure("invalid gRPC message: " + invalid.getMessage());
        }
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
    }

    private static ApiResponse fromStatus(Status status) {
        if (status.getCode() == Status.Code.DEADLINE_EXCEEDED) {
            return ApiResponse.timedOut();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("grpc-status", status.getCode().name());
        if (status.getDescription() != null) {
            headers.put("grpc-message", status.getDescription());
        }
        return ApiResponse.of(GrpcStatusMapping.httpEquivalent(status.getCode()), null, headers);
    }

    private Channel channel(GrpcConnection connection, String name) {
        ManagedChannel channel = channels.computeIfAbsent(name, ignored -> channelFactory.apply(connection));
        if (connection.metadata().isEmpty()) {
            return channel;
        }
        Metadata headers = new Metadata();
        connection.metadata().forEach((key, value) ->
                headers.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value));
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> methodDescriptor(ApiRequest request, MethodDescriptor method) {
        String key = request.connection() + "/" + method.getFullName();
        return methods.computeIfAbsent(key, ignored -> io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(
                        method.getService().getFullName(), method.getName()))
                .setRequestMarshaller(new DynamicMessageMarshaller(method.getInputType()))
                .setResponseMarshaller(new DynamicMessageMarshaller(method.getOutputType()))
                .build());
    }

    private static DynamicMessage toMessage(MethodDescriptor method, Object body) throws InvalidProtocolBufferException {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(method.getInputType());
        if (body != null) {
            JsonFormat.parser().merge(Json.write(body), builder);
        }
        return builder.build();
    }

    private static Object toTree(DynamicMessage reply) throws InvalidProtocolBufferException {
        return Json.parse(JsonFormat.printer().preservingProtoFieldNames().print(reply));
    }
}
