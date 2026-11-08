package br.com.lucaslima.enginerules.app.engine;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsSettings;

@ConfigurationProperties(prefix = "enginerules")
public record EngineProperties(
        String storeDirectory,
        Store store,
        Aws aws,
        Duration catalogRefreshInterval,
        Duration rolloutRefreshInterval,
        int compiledCacheMaxEntries,
        Duration resultCacheRetention,
        Duration connectTimeout,
        Execution execution,
        Trace trace,
        Shadow shadow,
        Metrics metrics,
        Map<String, Connection> connections,
        Map<String, GrpcConnection> grpcConnections) {

    public EngineProperties {
        store = store == null ? new Store(null) : store;
        connections = connections == null ? Map.of() : Map.copyOf(connections);
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

    public record Aws(String region, String endpoint, String tablePrefix, String snapshotBucket) {
        public AwsSettings toSettings() {
            Optional<URI> override = endpoint == null || endpoint.isBlank() ? Optional.empty() : Optional.of(URI.create(endpoint));
            return new AwsSettings(region, override, tablePrefix, snapshotBucket);
        }
    }

    public record GrpcConnection(String target, boolean plaintext, String descriptorSet, Map<String, String> metadata) {
        public GrpcConnection {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record Execution(Duration timeout, int stepBudget) {
    }

    public record Trace(boolean enabled, Set<String> policies) {
        public Trace {
            policies = policies == null ? Set.of() : Set.copyOf(policies);
        }
    }

    public record Shadow(int concurrency, int queueCapacity, List<String> ignoredPaths) {
        public Shadow {
            ignoredPaths = ignoredPaths == null ? List.of() : List.copyOf(ignoredPaths);
        }
    }

    public record Metrics(Duration flushInterval, int maxBufferedSamples) {
    }

    public record Connection(URI baseUrl, Map<String, String> headers) {
        public Connection {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }
}
