package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.lucaslima.enginerules.adapter.persistence.documents.PolicyDocuments;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListPoliciesPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyVersionPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public final class DynamoDbPolicyStore implements LoadPolicyPort, LoadPolicyVersionPort, SavePolicyPort, ListPoliciesPort {

    static final String META = "META";
    static final String INDEX_PARTITION = "IDX#ALL";
    static final String REVISION = "revision";
    static final String HASH = "hash";
    static final String STABLE = "ptr";
    static final String PENDING = "pendingCandidate";
    static final String OWNER = "owner";

    private final DynamoDbClient dynamo;
    private final AwsTables tables;

    public DynamoDbPolicyStore(DynamoDbClient dynamo, AwsTables tables) {
        this.dynamo = dynamo;
        this.tables = tables;
    }

    static String versionKey(SemanticVersion version) {
        return "v#" + version;
    }

    @Override
    public Optional<Policy> loadPolicy(PolicyName name, ReadConsistency consistency) {
        return item(tables.policies(), Items.key(name.value(), META))
                .map(Items::document)
                .map(PolicyDocuments::toSnapshot)
                .map(Policy::restore);
    }

    @Override
    public Optional<PolicyVersion> loadVersion(PolicyName name, SemanticVersion version) {
        return item(tables.policies(), Items.key(name.value(), versionKey(version)))
                .map(Items::document)
                .map(PolicyDocuments::toVersion);
    }

    @Override
    public void savePolicy(Policy policy, Optional<PolicyVersion> newVersion) {
        List<TransactWriteItem> writes = new ArrayList<>();
        newVersion.ifPresent(version -> writes.add(TransactWriteItem.builder().put(versionPut(version)).build()));
        writes.add(TransactWriteItem.builder().put(metaPut(policy)).build());
        writes.add(TransactWriteItem.builder().put(indexPut(policy)).build());
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build());
        } catch (TransactionCanceledException cancelled) {
            throw conflict(policy, newVersion, cancelled);
        }
    }

    @Override
    public Page<Policy> listPolicies(PolicyFilter filter, PageRequest page) {
        List<String> matching = Queries.all(dynamo, indexQuery(filter)).stream()
                .filter(entry -> filter.ownerGroup().map(owner -> owner.equals(Items.text(entry, OWNER).orElse(null))).orElse(true))
                .filter(entry -> filter.withPendingCandidate()
                        .map(expected -> expected.equals(entry.get(PENDING).bool())).orElse(true))
                .map(entry -> entry.get(Items.SORT).s())
                .sorted()
                .toList();
        Page<String> names = Page.slice(matching, page);
        return names.map(name -> loadPolicy(PolicyName.of(name), ReadConsistency.DIRECT).orElseThrow(() ->
                new IllegalStateException("policy index references missing policy " + name)));
    }

    private QueryRequest indexQuery(PolicyFilter filter) {
        if (filter.namePrefix().isEmpty()) {
            return Queries.partition(tables.policyIndex(), INDEX_PARTITION, true);
        }
        return QueryRequest.builder()
                .tableName(tables.policyIndex())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Items.names("#pk", Items.POLICY, "#sk", Items.SORT))
                .expressionAttributeValues(Items.values(":pk", Items.text(INDEX_PARTITION),
                        ":prefix", Items.text(filter.namePrefix().get())))
                .consistentRead(true)
                .build();
    }

    private Put versionPut(PolicyVersion version) {
        Map<String, AttributeValue> item = Items.key(version.name().value(), versionKey(version.version()));
        item.put(HASH, Items.text(version.hash().value()));
        item.put(Items.DOCUMENT, Items.document(PolicyDocuments.fromVersion(version)));
        return Put.builder()
                .tableName(tables.policies())
                .item(item)
                .conditionExpression("attribute_not_exists(#sk) OR #hash = :hash")
                .expressionAttributeNames(Items.names("#sk", Items.SORT, "#hash", HASH))
                .expressionAttributeValues(Items.values(":hash", Items.text(version.hash().value())))
                .build();
    }

    private Put metaPut(Policy policy) {
        long nextRevision = policy.revision() + 1;
        Map<String, AttributeValue> item = Items.key(policy.name().value(), META);
        item.put(REVISION, Items.number(nextRevision));
        item.put(STABLE, Items.text(policy.stable().toString()));
        policy.pendingCandidate().ifPresent(candidate -> item.put(PENDING, Items.text(candidate.toString())));
        item.put(OWNER, Items.text(policy.ownerGroup()));
        item.put(Items.DOCUMENT, Items.document(PolicyDocuments.fromSnapshot(policy.snapshot(), nextRevision)));
        Put.Builder put = Put.builder().tableName(tables.policies()).item(item);
        if (policy.revision() == 0) {
            return put.conditionExpression("attribute_not_exists(#sk)")
                    .expressionAttributeNames(Items.names("#sk", Items.SORT))
                    .build();
        }
        return put.conditionExpression("#revision = :expected")
                .expressionAttributeNames(Items.names("#revision", REVISION))
                .expressionAttributeValues(Items.values(":expected", Items.number(policy.revision())))
                .build();
    }

    private Put indexPut(Policy policy) {
        Map<String, AttributeValue> item = Items.key(INDEX_PARTITION, policy.name().value());
        item.put(OWNER, Items.text(policy.ownerGroup()));
        item.put(PENDING, Items.flag(policy.pendingCandidate().isPresent()));
        item.put(STABLE, Items.text(policy.stable().toString()));
        return Put.builder().tableName(tables.policyIndex()).item(item).build();
    }

    private Optional<Map<String, AttributeValue>> item(String table, Map<String, AttributeValue> key) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(table).key(key).consistentRead(true).build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }

    private static CatalogException conflict(Policy policy, Optional<PolicyVersion> newVersion, TransactionCanceledException cancelled) {
        List<CancellationReason> reasons = cancelled.cancellationReasons();
        boolean versionRejected = newVersion.isPresent() && !reasons.isEmpty()
                && "ConditionalCheckFailed".equals(reasons.get(0).code());
        String message = versionRejected
                ? "version " + policy.name() + "@" + newVersion.get().version() + " already exists with different content"
                : "policy " + policy.name() + " was modified concurrently; reload and retry";
        return new CatalogException(CatalogErrorCode.CONCURRENT_MODIFICATION, message);
    }
}
