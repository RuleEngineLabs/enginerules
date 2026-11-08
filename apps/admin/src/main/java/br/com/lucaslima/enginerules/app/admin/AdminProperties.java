package br.com.lucaslima.enginerules.app.admin;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsSettings;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;

@ConfigurationProperties(prefix = "enginerules")
public record AdminProperties(String storeDirectory, Store store, Aws aws, Canary canary,
                              Map<String, GrpcContract> grpcConnections) {

    public AdminProperties {
        store = store == null ? new Store(null) : store;
        grpcConnections = grpcConnections == null ? Map.of() : Map.copyOf(grpcConnections);
    }

    public record Store(Type type) {
        public Store {
            type = type == null ? Type.FILESYSTEM : type;
        }

        public enum Type {
            FILESYSTEM,
            AWS
        }
    }

    public record Aws(String region, String endpoint, String tablePrefix, String snapshotBucket, boolean createResources) {
        public AwsSettings toSettings() {
            Optional<URI> override = endpoint == null || endpoint.isBlank() ? Optional.empty() : Optional.of(URI.create(endpoint));
            return new AwsSettings(region, override, tablePrefix, snapshotBucket);
        }
    }

    public record GrpcContract(String descriptorSet) {
    }

    public record Canary(Duration evaluationInterval, Criteria criteria) {
    }

    public record Criteria(double errorMarginPercentagePoints, double maxLatencyRatio, long minSamples,
                           double maxInputIncompatibilityRate) {

        public CanaryCriteria toDomain() {
            return new CanaryCriteria(errorMarginPercentagePoints, maxLatencyRatio, minSamples, maxInputIncompatibilityRate);
        }
    }
}
