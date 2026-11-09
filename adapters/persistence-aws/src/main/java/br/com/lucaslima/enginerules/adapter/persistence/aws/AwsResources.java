package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.time.Duration;
import java.util.List;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

public final class AwsResources {

    private static final Duration POLL = Duration.ofMillis(200);
    private static final int MAX_POLLS = 150;
    private static final String US_EAST_1 = "us-east-1";

    private AwsResources() {
    }

    public static void ensure(DynamoDbClient dynamo, S3Client s3, AwsSettings settings) {
        AwsTables tables = settings.tables();
        for (String table : List.of(tables.policies(), tables.policyIndex(), tables.audit())) {
            createTable(dynamo, table, false);
        }
        for (String table : List.of(tables.canaries(), tables.shadows())) {
            createTable(dynamo, table, true);
        }
        createBucket(s3, settings);
    }

    static void createTable(DynamoDbClient dynamo, String table, boolean activeIndex) {
        CreateTableRequest.Builder request = CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(key(Items.POLICY, KeyType.HASH), key(Items.SORT, KeyType.RANGE));
        if (activeIndex) {
            request.attributeDefinitions(attribute(Items.POLICY), attribute(Items.SORT), attribute(DynamoDbRolloutStore.ACTIVE_STATUS))
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                            .indexName(AwsTables.ACTIVE_INDEX)
                            .keySchema(key(DynamoDbRolloutStore.ACTIVE_STATUS, KeyType.HASH), key(Items.POLICY, KeyType.RANGE))
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build());
        } else {
            request.attributeDefinitions(attribute(Items.POLICY), attribute(Items.SORT));
        }
        try {
            dynamo.createTable(request.build());
        } catch (ResourceInUseException alreadyExists) {
            return;
        }
        awaitActive(dynamo, table, MAX_POLLS, POLL);
    }

    static void createBucket(S3Client s3, AwsSettings settings) {
        CreateBucketRequest.Builder request = CreateBucketRequest.builder().bucket(settings.snapshotBucket());
        if (!US_EAST_1.equals(settings.region())) {
            request.createBucketConfiguration(CreateBucketConfiguration.builder().locationConstraint(settings.region()).build());
        }
        try {
            s3.createBucket(request.build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyExists) {
            return;
        }
    }

    static void awaitActive(DynamoDbClient dynamo, String table, int maxPolls, Duration pause) {
        for (int poll = 0; poll < maxPolls; poll++) {
            TableStatus status = dynamo.describeTable(DescribeTableRequest.builder().tableName(table).build()).table().tableStatus();
            if (status == TableStatus.ACTIVE) {
                return;
            }
            sleep(pause);
        }
        throw new IllegalStateException("table " + table + " did not become ACTIVE in time");
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a DynamoDB table", interrupted);
        }
    }

    private static KeySchemaElement key(String attribute, KeyType type) {
        return KeySchemaElement.builder().attributeName(attribute).keyType(type).build();
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }
}
