package br.com.lucaslima.enginerules.integration;

import static br.com.lucaslima.enginerules.integration.support.Definitions.ageGate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsClients;
import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsSettings;
import br.com.lucaslima.enginerules.adapter.persistence.aws.AwsTables;
import br.com.lucaslima.enginerules.integration.support.ApiResponse;
import br.com.lucaslima.enginerules.integration.support.Eventually;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

class AwsStorageIT extends IntegrationTest {

    private AwsSettings settings;
    private AwsTables tables;
    private DynamoDbClient dynamo;
    private S3Client s3;

    @BeforeEach
    void requireLocalStack() {
        Assumptions.assumeTrue(environment.aws().isPresent(), "runs only against LocalStack (ENGINERULES_IT_AWS_ENDPOINT)");
        settings = environment.aws().orElseThrow();
        tables = settings.tables();
        dynamo = AwsClients.dynamo(settings);
        s3 = AwsClients.s3(settings);
    }

    @Test
    void createKeepsMetaVersionAndIndexInTheSinglePolicyTable() {
        String name = uniqueName("AwsCreate");

        createPolicy(name, ageGate(18));

        Map<String, AttributeValue> meta = item(tables.policies(), name, "META");
        assertEquals("1.0.0", meta.get("ptr").s());
        assertEquals("1", meta.get("revision").n());
        assertEquals(OWNER_GROUP, meta.get("owner").s());
        assertTrue(item(tables.policies(), name, "v#1.0.0").get("doc").s().contains("input.age >= 18"));
        assertFalse(item(tables.policyIndex(), "IDX#ALL", name).get("pendingCandidate").bool());
    }

    @Test
    void publishingMarksThePendingCandidateInMetaAndIndex() {
        String name = uniqueName("AwsPublish");
        createPolicy(name, ageGate(18));

        String candidate = publishCandidate(name, "1.0.0", ageGate(21));

        assertEquals(candidate, item(tables.policies(), name, "META").get("pendingCandidate").s());
        assertEquals("2", item(tables.policies(), name, "META").get("revision").n());
        assertTrue(item(tables.policyIndex(), "IDX#ALL", name).get("pendingCandidate").bool());
        assertEquals(List.of("META", "v#1.0.0", "v#1.0.1"), sortKeys(tables.policies(), name));
    }

    @Test
    void theStableSnapshotInS3FollowsPromotionsAndRollbacks() {
        String name = uniqueName("AwsSnapshot");
        createPolicy(name, ageGate(18));
        assertTrue(snapshot(name).contains("\"version\":\"1.0.0\""));

        assertStatus(200, promote(name, publishCandidate(name, "1.0.0", ageGate(21))));
        assertTrue(snapshot(name).contains("input.age >= 21"));

        assertStatus(200, promote(name, "1.0.0"));
        assertTrue(snapshot(name).contains("input.age >= 18"));
    }

    @Test
    void auditRecordsLiveInTheirPolicyPartitionOrderedByTime() {
        String name = uniqueName("AwsAudit");
        createPolicy(name, ageGate(18));
        promote(name, publishCandidate(name, "1.0.0", ageGate(21)));

        List<String> keys = sortKeys(tables.audit(), name);

        assertEquals(3, keys.size());
        assertEquals(keys.stream().sorted().toList(), keys);
        assertEquals(List.of("stable.moved", "version.published", "policy.created"),
                ((List<?>) admin.get("/audits?policy=" + name).at("items")).stream()
                        .map(entry -> ((Map<?, ?>) entry).get("action")).toList());
    }

    @Test
    void openCanariesStayInTheSparseIndexOnlyWhileOpen() {
        String name = uniqueName("AwsCanary");
        createPolicy(name, ageGate(18));
        String candidate = publishCandidate(name, "1.0.0", ageGate(21));
        String id = assertStatus(201, admin.post("/policies/" + name + "/canaries", Map.of("candidate", candidate, "weight", 10)))
                .text("id");

        assertEquals("ACTIVE", item(tables.canaries(), name, id).get("gsiActiveStatus").s());
        awaitIndex(tables.canaries(), name, true);

        assertStatus(200, admin.delete("/policies/" + name + "/canaries/current"));
        assertFalse(item(tables.canaries(), name, id).containsKey("gsiActiveStatus"));
        awaitIndex(tables.canaries(), name, false);
    }

    @Test
    void liveShadowsStayInTheSparseIndexUntilTheyEnd() {
        String name = uniqueName("AwsShadow");
        createPolicy(name, ageGate(18));
        String target = publishCandidate(name, "1.0.0", ageGate(21));
        ApiResponse started = assertStatus(201, admin.post("/policies/" + name + "/shadows", Map.of("target", target, "percentage", 10)));
        String path = "/policies/" + name + "/shadows/" + started.text("id");

        assertStatus(200, admin.patch(path, Map.of("status", "PAUSED")));
        awaitIndex(tables.shadows(), name, true);
        assertStatus(200, admin.patch(path, Map.of("status", "ENDED")));

        awaitIndex(tables.shadows(), name, false);
        assertEquals("ENDED", admin.get("/policies/" + name + "/shadows").text("0.status"));
    }

    private Map<String, AttributeValue> item(String table, String partition, String sort) {
        return dynamo.getItem(GetItemRequest.builder().tableName(table).consistentRead(true)
                .key(Map.of("policyName", AttributeValue.fromS(partition), "sk", AttributeValue.fromS(sort))).build()).item();
    }

    private List<String> sortKeys(String table, String partition) {
        return dynamo.query(QueryRequest.builder().tableName(table).consistentRead(true)
                        .keyConditionExpression("policyName = :pk")
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(partition))).build())
                .items().stream().map(entry -> entry.get("sk").s()).toList();
    }

    private void awaitIndex(String table, String policy, boolean expected) {
        Eventually.await(() -> activeIn(table).contains(policy), present -> present == expected,
                policy + (expected ? " to enter " : " to leave ") + "the active index of " + table);
    }

    private List<String> activeIn(String table) {
        return dynamo.query(QueryRequest.builder().tableName(table).indexName(AwsTables.ACTIVE_INDEX)
                        .keyConditionExpression("gsiActiveStatus = :active")
                        .expressionAttributeValues(Map.of(":active", AttributeValue.fromS("ACTIVE"))).build())
                .items().stream().map(entry -> entry.get("policyName").s()).toList();
    }

    private String snapshot(String name) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(settings.snapshotBucket())
                .key("stable/" + name + ".json").build()).asUtf8String();
    }
}
