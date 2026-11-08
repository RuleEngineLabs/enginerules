package br.com.lucaslima.enginerules.adapter.persistence.aws;

import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.ANA;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.NOW;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.TABLES;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.created;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.definition;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionBump;

class StableSnapshotTest {

    private static final PolicyName NAME = PolicyName.of("CreditLimit");
    private static final String BUCKET = AwsFixtures.SETTINGS.snapshotBucket();

    private final InMemoryDynamoDb dynamo = AwsFixtures.dynamo();
    private final InMemoryS3 s3 = new InMemoryS3();
    private final DynamoDbPolicyStore store = new DynamoDbPolicyStore(dynamo, TABLES);
    private final S3StableSnapshots snapshots = new S3StableSnapshots(s3, BUCKET);
    private final SnapshotExportingPolicyStore exporting = new SnapshotExportingPolicyStore(store, store, snapshots);
    private final SnapshotFallbackCatalog fallback = new SnapshotFallbackCatalog(store, store, snapshots);

    StableSnapshotTest() {
        s3.bucket(BUCKET);
    }

    @Test
    void exportsOneObjectPerPolicyWithItsStableVersion() {
        exporting.savePolicy(created("CreditLimit", "credit-team"), Optional.of(version("CreditLimit", "1.0.0", 200)));

        S3StableSnapshots.StableSnapshot snapshot = snapshots.read(NAME).orElseThrow();

        assertTrue(s3.object(BUCKET, "stable/CreditLimit.json").contains("\"stable\""));
        assertEquals(SemanticVersion.FIRST, snapshot.stable().version());
        assertEquals(1L, snapshot.policy().revision());
        assertEquals(Optional.empty(), snapshots.read(PolicyName.of("Ghost")));
    }

    @Test
    void followsTheStableVersionAfterAPromotion() {
        exporting.savePolicy(created("CreditLimit", "credit-team"), Optional.of(version("CreditLimit", "1.0.0", 200)));
        Policy policy = store.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow();
        policy.publish(ANA, SemanticVersion.FIRST, ContentHash.of(definition(201)), VersionBump.PATCH, NOW);
        exporting.savePolicy(policy, Optional.of(version("CreditLimit", "1.0.1", 201)));
        assertEquals(SemanticVersion.FIRST, snapshots.read(NAME).orElseThrow().stable().version());
        Policy candidate = store.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow();
        candidate.moveStable(ANA, SemanticVersion.parse("1.0.1"), NOW.plusSeconds(1));

        exporting.savePolicy(candidate, Optional.empty());

        assertEquals(SemanticVersion.parse("1.0.1"), snapshots.read(NAME).orElseThrow().stable().version());
        assertEquals(definition(201), snapshots.read(NAME).orElseThrow().stable().definition());
    }

    @Test
    void aSnapshotFailureNeverUndoesTheCommittedWrite() {
        s3.unavailable(true);

        exporting.savePolicy(created("CreditLimit", "credit-team"), Optional.of(version("CreditLimit", "1.0.0", 200)));

        assertTrue(store.loadPolicy(NAME, ReadConsistency.DIRECT).isPresent());
        assertThrows(IllegalArgumentException.class, () -> snapshots.export(created("Other", "credit-team"), 1,
                version("Other", "1.0.1", 200)));
        assertThrows(IllegalStateException.class, () -> new SnapshotExportingPolicyStore(store, (name, version) -> Optional.empty(),
                snapshots).savePolicy(store.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow(), Optional.empty()));
    }

    @Test
    void servesTheSnapshotWhileDynamoDbIsUnreachable() {
        exporting.savePolicy(created("CreditLimit", "credit-team"), Optional.of(version("CreditLimit", "1.0.0", 200)));
        dynamo.unavailable(true);

        Policy policy = fallback.loadPolicy(NAME, ReadConsistency.CACHED).orElseThrow();

        assertTrue(fallback.usingFallback());
        assertEquals(SemanticVersion.FIRST, policy.stable());
        assertTrue(fallback.loadVersion(NAME, SemanticVersion.FIRST).isPresent());
        assertEquals(Optional.empty(), fallback.loadVersion(NAME, SemanticVersion.parse("1.0.1")));
        assertEquals(Optional.empty(), fallback.loadPolicy(PolicyName.of("Ghost"), ReadConsistency.CACHED));
    }

    @Test
    void leavesTheFallbackModeOnceDynamoDbAnswersAgain() {
        exporting.savePolicy(created("CreditLimit", "credit-team"), Optional.of(version("CreditLimit", "1.0.0", 200)));
        dynamo.unavailable(true);
        fallback.loadPolicy(NAME, ReadConsistency.CACHED);
        dynamo.unavailable(false);

        assertTrue(fallback.loadVersion(NAME, SemanticVersion.FIRST).isPresent());
        assertFalse(fallback.usingFallback());
        assertTrue(fallback.loadPolicy(NAME, ReadConsistency.DIRECT).isPresent());
    }
}
