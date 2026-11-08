package br.com.lucaslima.enginerules.app.engine;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsClients;
import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsSettings;
import br.com.lucaslima.enginerules.adapter.persistence.aws.DynamoDbPolicyStore;
import br.com.lucaslima.enginerules.adapter.persistence.aws.DynamoDbRolloutStore;
import br.com.lucaslima.enginerules.adapter.persistence.aws.S3StableSnapshots;
import br.com.lucaslima.enginerules.adapter.persistence.aws.SnapshotFallbackCatalog;
import br.com.lucaslima.enginerules.adapter.persistence.filesystem.FileSystemPolicyStore;
import br.com.lucaslima.enginerules.adapter.persistence.filesystem.FileSystemRolloutStore;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyVersionPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadCurrentShadowPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadOpenCanaryPort;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

record EngineStorage(
        LoadPolicyPort policies,
        LoadPolicyVersionPort versions,
        LoadOpenCanaryPort openCanaries,
        LoadCurrentShadowPort currentShadows,
        List<AutoCloseable> resources) implements AutoCloseable {

    static EngineStorage from(EngineProperties properties, Clock clock) {
        return switch (properties.store().type()) {
            case FILESYSTEM -> filesystem(Path.of(properties.storeDirectory()), clock);
            case AWS -> aws(properties.aws().toSettings(), clock);
        };
    }

    static EngineStorage filesystem(Path root, Clock clock) {
        FileSystemPolicyStore policies = new FileSystemPolicyStore(root);
        FileSystemRolloutStore rollouts = new FileSystemRolloutStore(root, clock);
        return new EngineStorage(policies, policies, rollouts, rollouts, List.of());
    }

    static EngineStorage aws(AwsSettings settings, Clock clock) {
        DynamoDbClient dynamo = AwsClients.dynamo(settings);
        S3Client s3 = AwsClients.s3(settings);
        DynamoDbPolicyStore policies = new DynamoDbPolicyStore(dynamo, settings.tables());
        SnapshotFallbackCatalog catalog = new SnapshotFallbackCatalog(policies, policies,
                new S3StableSnapshots(s3, settings.snapshotBucket()));
        DynamoDbRolloutStore rollouts = new DynamoDbRolloutStore(dynamo, settings.tables(), clock);
        return new EngineStorage(catalog, catalog, rollouts, rollouts, List.of(dynamo, s3));
    }

    @Override
    public void close() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }
}
