package br.com.lucaslima.enginerules.adapter.grpc;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;

import br.com.lucaslima.enginerules.domain.execution.spi.ServiceContracts;

public final class GrpcServiceContracts implements ServiceContracts {

    private final Map<String, GrpcDescriptors> descriptorsByConnection;

    public GrpcServiceContracts(Map<String, GrpcDescriptors> descriptorsByConnection) {
        this.descriptorsByConnection = Map.copyOf(descriptorsByConnection);
    }

    @Override
    public Optional<String> verifyGrpcCall(String connection, String service, String rpc, Set<String> messageFields) {
        GrpcDescriptors descriptors = descriptorsByConnection.get(connection);
        if (descriptors == null) {
            return Optional.of("unknown gRPC connection '" + connection + "'");
        }
        if (descriptors.service(service).isEmpty()) {
            return Optional.of("connection '" + connection + "' has no service '" + service + "'");
        }
        Optional<MethodDescriptor> method = descriptors.method(service, rpc);
        if (method.isEmpty()) {
            return Optional.of("service '" + service + "' has no rpc '" + rpc + "'");
        }
        if (method.get().isClientStreaming() || method.get().isServerStreaming()) {
            return Optional.of("rpc '" + rpc + "' is streaming; only unary calls are supported");
        }
        Descriptor input = method.get().getInputType();
        return messageFields.stream()
                .filter(field -> !hasField(input, field))
                .findFirst()
                .map(field -> "message type '" + input.getFullName() + "' has no field '" + field + "'");
    }

    static boolean hasField(Descriptor type, String field) {
        return type.findFieldByName(field) != null
                || type.getFields().stream().anyMatch(candidate -> candidate.getJsonName().equals(field));
    }
}
