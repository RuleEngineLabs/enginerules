package br.com.lucaslima.enginerules.adapter.persistence.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;

class AwsResourcesTest {

    @Test
    void createsEveryTableAndTheBucketOnce() {
        InMemoryDynamoDb dynamo = new InMemoryDynamoDb();
        InMemoryS3 s3 = new InMemoryS3();
        dynamo.describesBeforeActive(2);

        AwsResources.ensure(dynamo, s3, AwsFixtures.SETTINGS);
        AwsResources.ensure(dynamo, s3, AwsFixtures.SETTINGS);

        assertEquals(List.of("test-policies", "test-policy-index", "test-audit", "test-canaries", "test-shadows"),
                dynamo.created().stream().map(CreateTableRequest::tableName).toList());
        assertEquals(1, s3.created().size());
        assertEquals("sa-east-1", s3.created().get(0).createBucketConfiguration().locationConstraint());
    }

    @Test
    void addsTheSparseActiveIndexOnlyToRolloutTables() {
        InMemoryDynamoDb dynamo = new InMemoryDynamoDb();

        AwsResources.ensure(dynamo, new InMemoryS3(), AwsFixtures.SETTINGS);

        CreateTableRequest canaries = dynamo.created().get(3);
        assertEquals(AwsTables.ACTIVE_INDEX, canaries.globalSecondaryIndexes().get(0).indexName());
        assertEquals("gsiActiveStatus", canaries.globalSecondaryIndexes().get(0).keySchema().get(0).attributeName());
        assertNull(dynamo.created().get(0).globalSecondaryIndexes());
    }

    @Test
    void usEast1BucketsCarryNoLocationConstraint() {
        InMemoryS3 s3 = new InMemoryS3();

        AwsResources.createBucket(s3, new AwsSettings("us-east-1", Optional.empty(), null, "snapshots"));

        assertNull(s3.created().get(0).createBucketConfiguration());
    }

    @Test
    void givesUpWhenATableNeverBecomesActive() {
        InMemoryDynamoDb dynamo = new InMemoryDynamoDb();
        dynamo.describesBeforeActive(10);
        dynamo.createTable(CreateTableRequest.builder().tableName("slow").build());

        assertThrows(IllegalStateException.class, () -> AwsResources.awaitActive(dynamo, "slow", 2, Duration.ZERO));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> AwsResources.awaitActive(dynamo, "slow", 2, Duration.ofMillis(5)));
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void buildsClientsWithAndWithoutAnEndpointOverride() {
        AwsSettings local = new AwsSettings("sa-east-1", Optional.of(URI.create("http://localhost:4566")), "it-", "bucket");
        AwsSettings cloud = new AwsSettings("sa-east-1", null, null, "bucket");

        assertNotNull(AwsClients.dynamo(local));
        assertNotNull(AwsClients.s3(local));
        assertNotNull(AwsClients.dynamo(cloud));
        assertNotNull(AwsClients.s3(cloud));
        assertEquals("policies", cloud.tables().policies());
        assertEquals("it-shadows", local.tables().shadows());
        assertThrows(NullPointerException.class, () -> new AwsSettings(null, null, null, "bucket"));
    }
}
