package br.com.lucaslima.enginerules.adapter.persistence.aws;

import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.NOW;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.TABLES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.shared.PageRequest;

class DynamoDbAuditLogTest {

    private final InMemoryDynamoDb dynamo = AwsFixtures.dynamo();
    private final DynamoDbAuditLog log = new DynamoDbAuditLog(dynamo, TABLES);

    @Test
    void listsAPolicyTrailNewestFirstAcrossPages() {
        log.append(List.of(record("CreditLimit", "ana", "policy.created", NOW)));
        log.append(List.of(record("CreditLimit", "ana", "version.published", NOW.plusSeconds(1)),
                record("CreditLimit", "bia", "stable.moved", NOW.plusSeconds(1))));
        log.append(List.of(record("FraudCheck", "ana", "policy.created", NOW.plusSeconds(2))));

        List<String> actions = log.listAudits(AuditFilter.of("CreditLimit", null, null), PageRequest.of(1, 10)).items().stream()
                .map(AuditRecord::action).toList();

        assertEquals(List.of("stable.moved", "version.published", "policy.created"), actions);
        assertEquals(3, dynamo.items(TABLES.audit()).stream().filter(item -> item.get("policyName").s().equals("CreditLimit")).count());
    }

    @Test
    void scansEveryPolicyWhenNoPolicyIsGiven() {
        log.append(List.of(record("CreditLimit", "ana", "policy.created", NOW),
                record("FraudCheck", "bia", "policy.created", NOW.plusSeconds(5)),
                record("Pricing", "ana", "policy.created", NOW.plusSeconds(9))));

        var page = log.listAudits(AuditFilter.of(null, "ana", null), PageRequest.of(1, 1));

        assertEquals(2L, page.totalItems());
        assertEquals("Pricing", page.items().get(0).policy());
        assertEquals(List.of("CreditLimit"), log.listAudits(AuditFilter.of(null, "ana", null), PageRequest.of(2, 1)).items().stream()
                .map(AuditRecord::policy).toList());
    }

    @Test
    void filtersByActionAndKeepsTheDetails() {
        log.append(List.of(record("CreditLimit", "ana", "policy.created", NOW),
                new AuditRecord(NOW.plusSeconds(3), "CreditLimit", "ana", "stable.moved", Map.of("kind", "ROLLBACK"))));

        var moves = log.listAudits(AuditFilter.of("CreditLimit", null, "stable.moved"), PageRequest.of(1, 5));

        assertEquals(1L, moves.totalItems());
        assertEquals(Map.of("kind", "ROLLBACK"), moves.items().get(0).details());
        assertEquals("ana", dynamo.items(TABLES.audit()).get(0).get("actor").s());
    }

    @Test
    void sortKeysOrderChronologicallyWhateverTheFractionOfASecond() {
        String whole = DynamoDbAuditLog.sortKey(Instant.parse("2026-11-07T12:00:00Z"), 1, "a");
        String fraction = DynamoDbAuditLog.sortKey(Instant.parse("2026-11-07T12:00:00.500Z"), 1, "a");
        String sameInstantLater = DynamoDbAuditLog.sortKey(Instant.parse("2026-11-07T12:00:00Z"), 2, "a");

        assertTrue(whole.compareTo(fraction) < 0);
        assertTrue(whole.compareTo(sameInstantLater) < 0);
    }

    private static AuditRecord record(String policy, String actor, String action, Instant at) {
        return new AuditRecord(at, policy, actor, action, Map.of("version", "1.0.0"));
    }
}
