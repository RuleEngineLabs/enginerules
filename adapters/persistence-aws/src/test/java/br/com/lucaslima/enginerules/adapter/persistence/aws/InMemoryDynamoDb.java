package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class InMemoryDynamoDb implements DynamoDbClient {

    private static final int PAGE_SIZE = 2;
    private static final Pattern NOT_EXISTS = Pattern.compile("attribute_not_exists\\((#\\w+)\\)");
    private static final Pattern EQUALS = Pattern.compile("(#\\w+) = (:\\w+)");
    private static final Pattern BEGINS_WITH = Pattern.compile("begins_with\\((#\\w+), (:\\w+)\\)");

    private final Map<String, TreeMap<String, Map<String, AttributeValue>>> tables = new HashMap<>();
    private final Map<String, Integer> pendingCreation = new HashMap<>();
    private final List<CreateTableRequest> created = new ArrayList<>();
    private boolean unavailable;
    private int describesBeforeActive = 1;

    void unavailable(boolean value) {
        unavailable = value;
    }

    void describesBeforeActive(int value) {
        describesBeforeActive = value;
    }

    void createTestTable(String name) {
        tables.computeIfAbsent(name, ignored -> new TreeMap<>());
    }

    List<CreateTableRequest> created() {
        return created;
    }

    Map<String, AttributeValue> stored(String table, String partition, String sort) {
        return tables.get(table).get(partition + "|" + sort);
    }

    List<Map<String, AttributeValue>> items(String table) {
        return new ArrayList<>(tables.get(table).values());
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public void close() {
    }

    @Override
    public GetItemResponse getItem(GetItemRequest request) {
        available();
        Map<String, AttributeValue> item = table(request.tableName()).get(key(request.key()));
        return GetItemResponse.builder().item(item == null ? Map.of() : new LinkedHashMap<>(item)).build();
    }

    @Override
    public PutItemResponse putItem(PutItemRequest request) {
        available();
        Put put = Put.builder().tableName(request.tableName()).item(request.item())
                .conditionExpression(request.conditionExpression())
                .expressionAttributeNames(request.expressionAttributeNames())
                .expressionAttributeValues(request.expressionAttributeValues()).build();
        if (!satisfied(put)) {
            throw ConditionalCheckFailedException.builder().message("The conditional request failed").build();
        }
        apply(put);
        return PutItemResponse.builder().build();
    }

    @Override
    public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
        available();
        List<CancellationReason> reasons = new ArrayList<>();
        boolean cancelled = false;
        for (TransactWriteItem write : request.transactItems()) {
            boolean ok = satisfied(write.put());
            cancelled |= !ok;
            reasons.add(CancellationReason.builder().code(ok ? "None" : "ConditionalCheckFailed").build());
        }
        if (cancelled) {
            throw TransactionCanceledException.builder().message("Transaction cancelled").cancellationReasons(reasons).build();
        }
        request.transactItems().forEach(write -> apply(write.put()));
        return TransactWriteItemsResponse.builder().build();
    }

    @Override
    public QueryResponse query(QueryRequest request) {
        available();
        Map<String, String> names = request.expressionAttributeNames();
        Map<String, AttributeValue> values = request.expressionAttributeValues();
        String condition = request.keyConditionExpression();
        Matcher equality = EQUALS.matcher(condition);
        if (!equality.find()) {
            throw new IllegalArgumentException("unsupported key condition " + condition);
        }
        String attribute = names.get(equality.group(1));
        AttributeValue expected = values.get(equality.group(2));
        Matcher prefix = BEGINS_WITH.matcher(condition);
        String prefixAttribute = prefix.find() ? names.get(prefix.group(1)) : null;
        String prefixValue = prefixAttribute == null ? null : values.get(prefix.group(2)).s();
        List<Map<String, AttributeValue>> matching = new ArrayList<>(table(request.tableName()).values().stream()
                .filter(item -> expected.equals(item.get(attribute)))
                .filter(item -> prefixAttribute == null || item.get(prefixAttribute).s().startsWith(prefixValue))
                .sorted(Comparator.comparing(item -> item.get(request.indexName() == null ? Items.SORT : Items.POLICY).s()))
                .toList());
        if (Boolean.FALSE.equals(request.scanIndexForward())) {
            Collections.reverse(matching);
        }
        Page page = page(matching, request.exclusiveStartKey());
        return QueryResponse.builder().items(page.items()).lastEvaluatedKey(page.next()).build();
    }

    @Override
    public ScanResponse scan(ScanRequest request) {
        available();
        Page page = page(new ArrayList<>(table(request.tableName()).values()), request.exclusiveStartKey());
        return ScanResponse.builder().items(page.items()).lastEvaluatedKey(page.next()).build();
    }

    @Override
    public CreateTableResponse createTable(CreateTableRequest request) {
        available();
        if (tables.containsKey(request.tableName())) {
            throw ResourceInUseException.builder().message("Table already exists: " + request.tableName()).build();
        }
        created.add(request);
        tables.put(request.tableName(), new TreeMap<>());
        pendingCreation.put(request.tableName(), describesBeforeActive);
        return CreateTableResponse.builder().build();
    }

    @Override
    public DescribeTableResponse describeTable(DescribeTableRequest request) {
        int remaining = pendingCreation.merge(request.tableName(), -1, Integer::sum);
        TableStatus status = remaining >= 0 ? TableStatus.CREATING : TableStatus.ACTIVE;
        return DescribeTableResponse.builder()
                .table(TableDescription.builder().tableName(request.tableName()).tableStatus(status).build())
                .build();
    }

    private record Page(List<Map<String, AttributeValue>> items, Map<String, AttributeValue> next) {
    }

    private static Page page(List<Map<String, AttributeValue>> all, Map<String, AttributeValue> start) {
        int from = 0;
        if (start != null && !start.isEmpty()) {
            String startKey = key(start);
            while (from < all.size() && !key(all.get(from)).equals(startKey)) {
                from++;
            }
            from++;
        }
        int to = Math.min(from + PAGE_SIZE, all.size());
        List<Map<String, AttributeValue>> items = all.subList(Math.min(from, all.size()), to).stream()
                .map(item -> (Map<String, AttributeValue>) new LinkedHashMap<>(item)).toList();
        Map<String, AttributeValue> next = to < all.size() ? keyOf(all.get(to - 1)) : null;
        return new Page(items, next);
    }

    private boolean satisfied(Put put) {
        String condition = put.conditionExpression();
        if (condition == null) {
            return true;
        }
        Map<String, AttributeValue> existing = table(put.tableName()).get(key(put.item()));
        for (String alternative : condition.split(" OR ")) {
            Matcher notExists = NOT_EXISTS.matcher(alternative);
            Matcher equality = EQUALS.matcher(alternative);
            if (notExists.matches()) {
                if (existing == null || !existing.containsKey(put.expressionAttributeNames().get(notExists.group(1)))) {
                    return true;
                }
            } else if (equality.matches()) {
                AttributeValue current = existing == null ? null : existing.get(put.expressionAttributeNames().get(equality.group(1)));
                if (put.expressionAttributeValues().get(equality.group(2)).equals(current)) {
                    return true;
                }
            } else {
                throw new IllegalArgumentException("unsupported condition " + alternative);
            }
        }
        return false;
    }

    private void apply(Put put) {
        table(put.tableName()).put(key(put.item()), new LinkedHashMap<>(put.item()));
    }

    private TreeMap<String, Map<String, AttributeValue>> table(String name) {
        TreeMap<String, Map<String, AttributeValue>> table = tables.get(name);
        if (table == null) {
            throw new IllegalStateException("test table " + name + " was not created");
        }
        return table;
    }

    private void available() {
        if (unavailable) {
            throw SdkClientException.create("Unable to execute HTTP request: Connection refused");
        }
    }

    private static Map<String, AttributeValue> keyOf(Map<String, AttributeValue> item) {
        return Items.key(item.get(Items.POLICY).s(), item.get(Items.SORT).s());
    }

    private static String key(Map<String, AttributeValue> item) {
        return item.get(Items.POLICY).s() + "|" + item.get(Items.SORT).s();
    }
}
