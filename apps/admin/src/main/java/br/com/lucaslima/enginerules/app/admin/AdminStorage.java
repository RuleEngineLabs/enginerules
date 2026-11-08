package br.com.lucaslima.enginerules.app.admin;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsClients;
import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsResources;
import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsSettings;
import br.com.lucaslima.enginerules.adapter.persistence.aws.DynamoDbAuditLog;
import br.com.lucaslima.enginerules.adapter.persistence.aws.DynamoDbPolicyStore;
import br.com.lucaslima.enginerules.adapter.persistence.aws.DynamoDbRolloutStore;
import br.com.lucaslima.enginerules.adapter.persistence.aws.S3StableSnapshots;
import br.com.lucaslima.enginerules.adapter.persistence.aws.SnapshotExportingPolicyStore;
import br.com.lucaslima.enginerules.adapter.persistence.filesystem.FileSystemAuditLog;
import br.com.lucaslima.enginerules.adapter.persistence.filesystem.FileSystemPolicyStore;
import br.com.lucaslima.enginerules.adapter.persistence.filesystem.FileSystemRolloutStore;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListAuditsPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListPoliciesPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyVersionPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.RolloutGuardPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.CanaryStorePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadCurrentShadowPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadOpenCanaryPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowStorePort;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

record AdminStorage(
        LoadPolicyPort loadPolicies,
        LoadPolicyVersionPort loadVersions,
        SavePolicyPort savePolicies,
        ListPoliciesPort listPolicies,
        AppendAuditPort appendAudit,
        ListAuditsPort listAudits,
        CanaryStorePort canaries,
        ShadowStorePort shadows,
        LoadOpenCanaryPort openCanaries,
        LoadCurrentShadowPort currentShadows,
        RolloutGuardPort rolloutGuard,
        List<AutoCloseable> resources) implements AutoCloseable {

    static AdminStorage from(AdminProperties properties, Clock clock) {
        return switch (properties.store().type()) {
            case FILESYSTEM -> filesystem(Path.of(properties.storeDirectory()), clock);
            case AWS -> aws(properties.aws(), clock);
        };
    }

    static AdminStorage filesystem(Path root, Clock clock) {
        FileSystemPolicyStore policies = new FileSystemPolicyStore(root);
        FileSystemAuditLog audit = new FileSystemAuditLog(root);
        FileSystemRolloutStore rollouts = new FileSystemRolloutStore(root, clock);
        return new AdminStorage(policies, policies, policies, policies, audit, audit,
                rollouts, rollouts, rollouts, rollouts, rollouts, List.of());
    }

    static AdminStorage aws(AdminProperties.Aws aws, Clock clock) {
        AwsSettings settings = aws.toSettings();
        DynamoDbClient dynamo = AwsClients.dynamo(settings);
        S3Client s3 = AwsClients.s3(settings);
        if (aws.createResources()) {
            AwsResources.ensure(dynamo, s3, settings);
        }
        DynamoDbPolicyStore policies = new DynamoDbPolicyStore(dynamo, settings.tables());
        DynamoDbAuditLog audit = new DynamoDbAuditLog(dynamo, settings.tables());
        DynamoDbRolloutStore rollouts = new DynamoDbRolloutStore(dynamo, settings.tables(), clock);
        SavePolicyPort exporting = new SnapshotExportingPolicyStore(policies, policies,
                new S3StableSnapshots(s3, settings.snapshotBucket()));
        return new AdminStorage(policies, policies, exporting, policies, audit, audit,
                rollouts, rollouts, rollouts, rollouts, rollouts, List.of(dynamo, s3));
    }

    @Override
    public void close() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }
}
