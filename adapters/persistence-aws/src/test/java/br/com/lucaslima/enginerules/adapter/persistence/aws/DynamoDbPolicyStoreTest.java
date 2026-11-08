package br.com.lucaslima.enginerules.adapter.persistence.aws;

import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.ANA;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.NOW;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.TABLES;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.created;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.definition;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionBump;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

class DynamoDbPolicyStoreTest {

    private final InMemoryDynamoDb dynamo = AwsFixtures.dynamo();
    private final DynamoDbPolicyStore store = new DynamoDbPolicyStore(dynamo, TABLES);

    @Test
    void storesMetaVersionAndIndexItemsInOneTransaction() {
        Policy policy = created("CreditLimit", "credit-team");

        store.savePolicy(policy, Optional.of(version("CreditLimit", "1.0.0", 200)));

        Policy loaded = store.loadPolicy(PolicyName.of("CreditLimit"), ReadConsistency.DIRECT).orElseThrow();
        assertEquals(1L, loaded.revision());
        assertEquals("1", dynamo.stored(TABLES.policies(), "CreditLimit", "META").get("revision").n());
        assertEquals("1.0.0", dynamo.stored(TABLES.policies(), "CreditLimit", "META").get("ptr").s());
        assertEquals(Boolean.FALSE, dynamo.stored(TABLES.policyIndex(), "IDX#ALL", "CreditLimit").get("pendingCandidate").bool());
        assertEquals(definition(200), store.loadVersion(PolicyName.of("CreditLimit"), SemanticVersion.FIRST).orElseThrow().definition());
    }

    @Test
    void recordsThePendingCandidateAndBumpsTheRevision() {
        store.savePolicy(created("CreditLimit", "credit-team"), Optional.of(version("CreditLimit", "1.0.0", 200)));
        Policy policy = store.loadPolicy(PolicyName.of("CreditLimit"), ReadConsistency.DIRECT).orElseThrow();
        policy.publish(ANA, SemanticVersion.FIRST, ContentHash.of(definition(201)), VersionBump.PATCH, NOW);

        store.savePolicy(policy, Optional.of(version("CreditLimit", "1.0.1", 201)));

        assertEquals("1.0.1", dynamo.stored(TABLES.policies(), "CreditLimit", "META").get("pendingCandidate").s());
        assertEquals(Boolean.TRUE, dynamo.stored(TABLES.policyIndex(), "IDX#ALL", "CreditLimit").get("pendingCandidate").bool());
        assertEquals(2L, store.loadPolicy(PolicyName.of("CreditLimit"), ReadConsistency.CACHED).orElseThrow().revision());
    }

    @Test
    void rejectsStaleRevisionsAndDuplicatedCreations() {
        store.savePolicy(created("CreditLimit", "credit-team"), Optional.of(version("CreditLimit", "1.0.0", 200)));
        Policy first = store.loadPolicy(PolicyName.of("CreditLimit"), ReadConsistency.DIRECT).orElseThrow();
        Policy second = store.loadPolicy(PolicyName.of("CreditLimit"), ReadConsistency.DIRECT).orElseThrow();
        store.savePolicy(first, Optional.empty());

        CatalogException stale = assertThrows(CatalogException.class, () -> store.savePolicy(second, Optional.empty()));
        CatalogException duplicated = assertThrows(CatalogException.class,
                () -> store.savePolicy(created("CreditLimit", "credit-team"), Optional.empty()));

        assertEquals(CatalogErrorCode.CONCURRENT_MODIFICATION, stale.code());
        assertTrue(stale.getMessage().contains("modified concurrently"));
        assertEquals(CatalogErrorCode.CONCURRENT_MODIFICATION, duplicated.code());
    }

    @Test
    void neverOverwritesAnImmutableVersionWithOtherContent() {
        store.savePolicy(created("CreditLimit", "credit-team"), Optional.of(version("CreditLimit", "1.0.0", 200)));
        Policy loaded = store.loadPolicy(PolicyName.of("CreditLimit"), ReadConsistency.DIRECT).orElseThrow();

        CatalogException failure = assertThrows(CatalogException.class,
                () -> store.savePolicy(loaded, Optional.of(version("CreditLimit", "1.0.0", 201))));
        store.savePolicy(loaded, Optional.of(version("CreditLimit", "1.0.0", 200)));

        assertTrue(failure.getMessage().contains("already exists with different content"));
        assertEquals(2L, store.loadPolicy(PolicyName.of("CreditLimit"), ReadConsistency.DIRECT).orElseThrow().revision());
    }

    @Test
    void listsThroughTheIndexWithFiltersAndPages() {
        for (String name : List.of("CreditLimit", "CreditScore", "CreditTier", "FraudCheck")) {
            store.savePolicy(created(name, name.startsWith("Fraud") ? "fraud-team" : "credit-team"),
                    Optional.of(version(name, "1.0.0", 200)));
        }
        Policy pending = store.loadPolicy(PolicyName.of("CreditScore"), ReadConsistency.DIRECT).orElseThrow();
        pending.publish(ANA, SemanticVersion.FIRST, ContentHash.of(definition(201)), VersionBump.PATCH, NOW);
        store.savePolicy(pending, Optional.of(version("CreditScore", "1.0.1", 201)));

        assertEquals(List.of("CreditLimit", "CreditScore", "CreditTier"), names(PolicyFilter.of("Credit", null, null), 1, 10));
        assertEquals(List.of("FraudCheck"), names(PolicyFilter.of(null, "fraud-team", null), 1, 10));
        assertEquals(List.of("CreditScore"), names(PolicyFilter.of(null, null, true), 1, 10));
        assertEquals(List.of("CreditTier", "FraudCheck"), names(PolicyFilter.of(null, null, null), 2, 2));
        assertEquals(4L, store.listPolicies(PolicyFilter.of(null, null, null), PageRequest.of(2, 3)).totalItems());
    }

    @Test
    void reportsAbsentItemsAndBrokenIndexEntries() {
        InMemoryDynamoDb indexWithoutItems = new InMemoryDynamoDb() {
            @Override
            public GetItemResponse getItem(GetItemRequest request) {
                return GetItemResponse.builder().build();
            }
        };
        indexWithoutItems.createTestTable(TABLES.policies());
        indexWithoutItems.createTestTable(TABLES.policyIndex());
        DynamoDbPolicyStore broken = new DynamoDbPolicyStore(indexWithoutItems, TABLES);
        broken.savePolicy(created("Orphan", "credit-team"), Optional.empty());

        assertEquals(Optional.empty(), store.loadPolicy(PolicyName.of("Ghost"), ReadConsistency.DIRECT));
        assertEquals(Optional.empty(), store.loadVersion(PolicyName.of("Ghost"), SemanticVersion.FIRST));
        assertEquals(Optional.empty(), broken.loadPolicy(PolicyName.of("Orphan"), ReadConsistency.DIRECT));
        assertThrows(IllegalStateException.class, () -> broken.listPolicies(PolicyFilter.of(null, null, null), PageRequest.of(1, 5)));
        assertEquals(0L, store.listPolicies(PolicyFilter.of(null, null, null), PageRequest.of(1, 5)).totalItems());
    }

    private List<String> names(PolicyFilter filter, int pageNumber, int pageSize) {
        return store.listPolicies(filter, PageRequest.of(pageNumber, pageSize)).items().stream()
                .map(policy -> policy.name().value()).toList();
    }
}
