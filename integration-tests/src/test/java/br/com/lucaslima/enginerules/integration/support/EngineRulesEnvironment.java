package br.com.lucaslima.enginerules.integration.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsSettings;
import br.com.lucaslima.enginerules.app.admin.AdminApplication;
import br.com.lucaslima.enginerules.app.engine.EngineApplication;

public final class EngineRulesEnvironment {

    private static final String AWS_ENDPOINT = "ENGINERULES_IT_AWS_ENDPOINT";

    private static volatile EngineRulesEnvironment shared;

    private final Optional<AwsSettings> aws;
    private final FakeRestProvider restProvider;
    private final FakeGrpcProvider grpcProvider;
    private final RunningApplication adminApplication;
    private final RunningApplication engineApplication;

    private EngineRulesEnvironment(Path store) {
        aws = awsFromEnvironment();
        restProvider = FakeRestProvider.start();
        grpcProvider = FakeGrpcProvider.start();
        adminApplication = Applications.start(AdminApplication.class, adminProperties(store));
        engineApplication = Applications.start(EngineApplication.class, engineProperties(store));
    }

    public static EngineRulesEnvironment shared() {
        if (shared == null) {
            synchronized (EngineRulesEnvironment.class) {
                if (shared == null) {
                    EngineRulesEnvironment started = new EngineRulesEnvironment(temporaryStore());
                    Runtime.getRuntime().addShutdownHook(new Thread(started::stop, "enginerules-it-shutdown"));
                    shared = started;
                }
            }
        }
        return shared;
    }

    public ApiClient admin() {
        return new ApiClient(adminApplication.baseUri());
    }

    public ApiClient engine() {
        return new ApiClient(engineApplication.baseUri());
    }

    public Optional<AwsSettings> aws() {
        return aws;
    }

    public FakeRestProvider rest() {
        return restProvider;
    }

    public FakeGrpcProvider grpc() {
        return grpcProvider;
    }

    private void stop() {
        engineApplication.close();
        adminApplication.close();
        grpcProvider.close();
        restProvider.close();
    }

    private Map<String, String> engineProperties(Path store) {
        Map<String, String> properties = common("enginerules-engine", store);
        properties.put("enginerules.catalog-refresh-interval", "0s");
        properties.put("enginerules.rollout-refresh-interval", "0s");
        properties.put("enginerules.compiled-cache-max-entries", "500");
        properties.put("enginerules.result-cache-retention", "30m");
        properties.put("enginerules.connect-timeout", "2s");
        properties.put("enginerules.execution.timeout", "5s");
        properties.put("enginerules.execution.step-budget", "200");
        properties.put("enginerules.trace.enabled", "false");
        properties.put("enginerules.shadow.concurrency", "2");
        properties.put("enginerules.shadow.queue-capacity", "500");
        properties.put("enginerules.metrics.flush-interval", "50ms");
        properties.put("enginerules.metrics.max-buffered-samples", "100000");
        properties.put("enginerules.connections.provider.base-url", restProvider.baseUri().toString());
        properties.put("enginerules.connections.provider.headers[X-Tenant]", "acme");
        properties.put("enginerules.grpc-connections.bureau.target", grpcProvider.target());
        properties.put("enginerules.grpc-connections.bureau.plaintext", "true");
        properties.put("enginerules.grpc-connections.bureau.descriptor-set", grpcProvider.descriptorSet().toString());
        properties.put("enginerules.grpc-connections.bureau.metadata[x-tenant]", "acme");
        aws.ifPresent(settings -> properties.putAll(awsProperties(settings)));
        return properties;
    }

    private Map<String, String> adminProperties(Path store) {
        Map<String, String> properties = common("enginerules-admin", store);
        properties.put("enginerules.canary.evaluation-interval", "1h");
        properties.put("enginerules.canary.criteria.error-margin-percentage-points", "0.5");
        properties.put("enginerules.canary.criteria.max-latency-ratio", "1000");
        properties.put("enginerules.canary.criteria.min-samples", "20");
        properties.put("enginerules.canary.criteria.max-input-incompatibility-rate", "0.0");
        properties.put("enginerules.grpc-connections.bureau.descriptor-set", grpcProvider.descriptorSet().toString());
        aws.ifPresent(settings -> {
            properties.putAll(awsProperties(settings));
            properties.put("enginerules.aws.create-resources", "true");
        });
        return properties;
    }

    private static Map<String, String> awsProperties(AwsSettings settings) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("enginerules.store.type", "aws");
        properties.put("enginerules.aws.region", settings.region());
        properties.put("enginerules.aws.endpoint", settings.endpoint().orElseThrow().toString());
        properties.put("enginerules.aws.table-prefix", settings.tablePrefix());
        properties.put("enginerules.aws.snapshot-bucket", settings.snapshotBucket());
        return properties;
    }

    private static Optional<AwsSettings> awsFromEnvironment() {
        String endpoint = System.getenv(AWS_ENDPOINT);
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        defaultCredential("aws.accessKeyId", "AWS_ACCESS_KEY_ID");
        defaultCredential("aws.secretAccessKey", "AWS_SECRET_ACCESS_KEY");
        String run = Long.toString(System.currentTimeMillis(), 36);
        String region = Optional.ofNullable(System.getenv("AWS_REGION")).orElse("sa-east-1");
        return Optional.of(new AwsSettings(region, Optional.of(URI.create(endpoint)), "it-" + run + "-",
                "enginerules-it-" + run));
    }

    private static void defaultCredential(String property, String variable) {
        if (System.getenv(variable) == null && System.getProperty(property) == null) {
            System.setProperty(property, "test");
        }
    }

    private static Map<String, String> common(String applicationName, Path store) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.application.name", applicationName);
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.threads.virtual.enabled", "true");
        properties.put("logging.level.root", "warn");
        properties.put("enginerules.store-directory", store.toString());
        return properties;
    }

    private static Path temporaryStore() {
        try {
            return Files.createTempDirectory("enginerules-it");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
