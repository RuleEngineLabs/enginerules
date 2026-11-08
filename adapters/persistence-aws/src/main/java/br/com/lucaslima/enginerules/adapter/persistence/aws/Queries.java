package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

final class Queries {

    private Queries() {
    }

    static List<Map<String, AttributeValue>> all(DynamoDbClient dynamo, QueryRequest request) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        QueryRequest page = request;
        while (true) {
            QueryResponse response = dynamo.query(page);
            items.addAll(response.items());
            if (!response.hasLastEvaluatedKey() || response.lastEvaluatedKey().isEmpty()) {
                return items;
            }
            page = page.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build();
        }
    }

    static List<Map<String, AttributeValue>> all(DynamoDbClient dynamo, ScanRequest request) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        ScanRequest page = request;
        while (true) {
            ScanResponse response = dynamo.scan(page);
            items.addAll(response.items());
            if (!response.hasLastEvaluatedKey() || response.lastEvaluatedKey().isEmpty()) {
                return items;
            }
            page = page.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build();
        }
    }

    static QueryRequest partition(String table, String partition, boolean consistent) {
        return QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Items.names("#pk", Items.POLICY))
                .expressionAttributeValues(Items.values(":pk", Items.text(partition)))
                .consistentRead(consistent)
                .build();
    }
}
