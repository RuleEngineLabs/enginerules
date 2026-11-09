package br.com.lucaslima.enginerules.adapter.grpc;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record GrpcConnection(String target, boolean plaintext, Path descriptorSet, Map<String, String> metadata) {

    public GrpcConnection {
        Objects.requireNonNull(descriptorSet, "descriptorSet");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
