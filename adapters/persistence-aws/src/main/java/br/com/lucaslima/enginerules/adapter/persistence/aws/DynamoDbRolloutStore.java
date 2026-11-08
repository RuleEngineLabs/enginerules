package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.lucaslima.enginerules.adapter.persistence.documents.RolloutDocuments;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.RolloutGuardPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.CanaryStorePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadCurrentShadowPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadOpenCanaryPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowStorePort;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

public final class DynamoDbRolloutStore implements LoadOpenCanaryPort, CanaryStorePort, LoadCurrentShadowPort,
        ShadowStorePort, RolloutGuardPort {

    static final String ACTIVE_STATUS = "gsiActiveStatus";
    static final String ACTIVE = "ACTIVE";
    static final String STARTED_AT = "startedAt";

    private final DynamoDbClient dynamo;
    private final AwsTables tables;
    private final Clock clock;

    public DynamoDbRolloutStore(DynamoDbClient dynamo, AwsTables tables, Clock clock) {
        this.dynamo = dynamo;
        this.tables = tables;
        this.clock = clock;
    }

    @Override
    public Optional<Canary> findOpen(PolicyName name, ReadConsistency consistency) {
        return canaryHistory(name).stream().filter(canary -> canary.status().isOpen()).findFirst();
    }

    @Override
    public Optional<Canary> findCanary(PolicyName name, String id) {
        return item(tables.canaries(), name, id).map(Items::document).map(RolloutDocuments::toCanary).map(Canary::restore);
    }

    @Override
    public List<Canary> canaryHistory(PolicyName name) {
        return Queries.all(dynamo, Queries.partition(tables.canaries(), name.value(), true)).stream()
                .map(Items::document).map(RolloutDocuments::toCanary).map(Canary::restore)
                .sorted(Comparator.comparing(Canary::startedAt).reversed())
                .toList();
    }

    @Override
    public List<Canary> listOpen() {
        return Queries.all(dynamo, activeIndex(tables.canaries())).stream()
                .map(Items::document).map(RolloutDocuments::toCanary).map(Canary::restore)
                .filter(canary -> canary.status().isOpen())
                .toList();
    }

    @Override
    public void save(Canary canary) {
        Map<String, Object> document = RolloutDocuments.fromCanary(canary.snapshot(), canary.revision() + 1);
        put(tables.canaries(), canary.policy(), canary.id(), document, canary.status().isOpen(), canary.startedAt().toString());
    }

    @Override
    public Optional<Shadow> findCurrent(PolicyName name, ReadConsistency consistency) {
        return shadowHistory(name).stream().filter(DynamoDbRolloutStore::isLive).findFirst();
    }

    @Override
    public Optional<Shadow> findShadow(PolicyName name, String id) {
        return item(tables.shadows(), name, id).map(Items::document).map(RolloutDocuments::toShadow).map(Shadow::restore);
    }

    @Override
    public List<Shadow> shadowHistory(PolicyName name) {
        return Queries.all(dynamo, Queries.partition(tables.shadows(), name.value(), true)).stream()
                .map(Items::document).map(RolloutDocuments::toShadow).map(Shadow::restore)
                .sorted(Comparator.comparing((Shadow shadow) -> shadow.snapshot().startedAt()).reversed())
                .toList();
    }

    @Override
    public void save(Shadow shadow) {
        Map<String, Object> document = RolloutDocuments.fromShadow(shadow.snapshot(), shadow.revision() + 1);
        put(tables.shadows(), shadow.policy(), shadow.id(), document, isLive(shadow), shadow.snapshot().startedAt().toString());
    }

    @Override
    public boolean hasOpenCanary(PolicyName name) {
        return findOpen(name, ReadConsistency.DIRECT).isPresent();
    }

    @Override
    public boolean isVersionInUse(PolicyName name, SemanticVersion version) {
        boolean canaryCandidate = findOpen(name, ReadConsistency.DIRECT)
                .map(canary -> canary.candidate().equals(version)).orElse(false);
        boolean shadowTarget = findCurrent(name, ReadConsistency.DIRECT)
                .filter(shadow -> shadow.isLiveAt(clock.instant()))
                .map(shadow -> shadow.target().equals(version)).orElse(false);
        return canaryCandidate || shadowTarget;
    }

    private static boolean isLive(Shadow shadow) {
        ShadowStatus status = shadow.snapshot().status();
        return status == ShadowStatus.ACTIVE || status == ShadowStatus.PAUSED;
    }

    private void put(String table, PolicyName policy, String id, Map<String, Object> document, boolean active, String startedAt) {
        Map<String, AttributeValue> item = Items.key(policy.value(), id);
        item.put(STARTED_AT, Items.text(startedAt));
        item.put(Items.DOCUMENT, Items.document(document));
        if (active) {
            item.put(ACTIVE_STATUS, Items.text(ACTIVE));
        }
        dynamo.putItem(PutItemRequest.builder().tableName(table).item(item).build());
    }

    private QueryRequest activeIndex(String table) {
        return QueryRequest.builder()
                .tableName(table)
                .indexName(AwsTables.ACTIVE_INDEX)
                .keyConditionExpression("#status = :active")
                .expressionAttributeNames(Items.names("#status", ACTIVE_STATUS))
                .expressionAttributeValues(Items.values(":active", Items.text(ACTIVE)))
                .build();
    }

    private Optional<Map<String, AttributeValue>> item(String table, PolicyName policy, String id) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(table).key(Items.key(policy.value(), id)).consistentRead(true).build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }
}
