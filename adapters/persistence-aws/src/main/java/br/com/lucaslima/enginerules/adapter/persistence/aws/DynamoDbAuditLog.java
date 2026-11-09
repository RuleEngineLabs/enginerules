package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import br.com.lucaslima.enginerules.adapter.persistence.documents.AuditDocuments;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListAuditsPort;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

public final class DynamoDbAuditLog implements AppendAuditPort, ListAuditsPort {

    static final String ACTOR = "actor";
    static final String ACTION = "action";

    private final DynamoDbClient dynamo;
    private final String table;
    private final AtomicLong sequence = new AtomicLong();

    public DynamoDbAuditLog(DynamoDbClient dynamo, AwsTables tables) {
        this.dynamo = dynamo;
        this.table = tables.audit();
    }

    static String sortKey(Instant occurredAt, long sequence, String unique) {
        long micros = occurredAt.getEpochSecond() * 1_000_000L + occurredAt.getNano() / 1_000L;
        return String.format("%020d#%012d#%s", micros, sequence, unique);
    }

    @Override
    public void append(List<AuditRecord> records) {
        for (AuditRecord record : records) {
            Map<String, AttributeValue> item = Items.key(record.policy(),
                    sortKey(record.occurredAt(), sequence.incrementAndGet(), UUID.randomUUID().toString()));
            item.put(ACTOR, Items.text(record.actor()));
            item.put(ACTION, Items.text(record.action()));
            item.put(Items.DOCUMENT, Items.document(AuditDocuments.fromRecord(record)));
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(item)
                    .conditionExpression("attribute_not_exists(#sk)")
                    .expressionAttributeNames(Items.names("#sk", Items.SORT))
                    .build());
        }
    }

    @Override
    public Page<AuditRecord> listAudits(AuditFilter filter, PageRequest page) {
        List<Map<String, AttributeValue>> items = filter.policy()
                .map(policy -> Queries.all(dynamo, Queries.partition(table, policy, true).toBuilder().scanIndexForward(false).build()))
                .orElseGet(() -> Queries.all(dynamo, ScanRequest.builder().tableName(table).consistentRead(true).build()));
        List<AuditRecord> matching = items.stream()
                .sorted(Comparator.comparing((Map<String, AttributeValue> item) -> item.get(Items.SORT).s()).reversed())
                .map(item -> AuditDocuments.toRecord(Items.document(item)))
                .filter(filter::matches)
                .toList();
        return Page.slice(matching, page);
    }
}
