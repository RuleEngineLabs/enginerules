package br.com.lucaslima.enginerules.adapter.grpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

public final class GrpcDescriptors {

    private final Map<String, ServiceDescriptor> services;

    private GrpcDescriptors(Map<String, ServiceDescriptor> services) {
        this.services = Map.copyOf(services);
    }

    public static GrpcDescriptors load(Path descriptorSet) {
        try {
            return from(FileDescriptorSet.parseFrom(Files.readAllBytes(descriptorSet)));
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read gRPC descriptor set " + descriptorSet, failure);
        }
    }

    public static GrpcDescriptors from(FileDescriptorSet set) {
        Map<String, FileDescriptorProto> protos = new HashMap<>();
        set.getFileList().forEach(proto -> protos.put(proto.getName(), proto));
        Map<String, FileDescriptor> built = new HashMap<>();
        Map<String, ServiceDescriptor> services = new HashMap<>();
        for (FileDescriptorProto proto : set.getFileList()) {
            FileDescriptor file = build(proto.getName(), protos, built);
            file.getServices().forEach(service -> services.put(service.getFullName(), service));
        }
        return new GrpcDescriptors(services);
    }

    public Optional<ServiceDescriptor> service(String fullName) {
        return Optional.ofNullable(services.get(fullName));
    }

    public Optional<MethodDescriptor> method(String service, String rpc) {
        return service(service).map(descriptor -> descriptor.findMethodByName(rpc));
    }

    private static FileDescriptor build(String name, Map<String, FileDescriptorProto> protos, Map<String, FileDescriptor> built) {
        FileDescriptor existing = built.get(name);
        if (existing != null) {
            return existing;
        }
        FileDescriptorProto proto = protos.get(name);
        if (proto == null) {
            throw new IllegalArgumentException("descriptor set is missing dependency " + name
                    + "; generate it with protoc --include_imports");
        }
        List<FileDescriptor> dependencies = new ArrayList<>();
        proto.getDependencyList().forEach(dependency -> dependencies.add(build(dependency, protos, built)));
        try {
            FileDescriptor file = FileDescriptor.buildFrom(proto, dependencies.toArray(new FileDescriptor[0]));
            built.put(name, file);
            return file;
        } catch (DescriptorValidationException invalid) {
            throw new IllegalArgumentException("invalid descriptor " + name + ": " + invalid.getMessage(), invalid);
        }
    }
}
