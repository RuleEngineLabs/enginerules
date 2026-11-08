package br.com.lucaslima.enginerules.adapter.persistence.aws;

import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.NOW;
import static br.com.lucaslima.enginerules.adapter.persistence.aws.AwsFixtures.TABLES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryStatus;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;

class DynamoDbRolloutStoreTest {

    private static final PolicyName CREDIT = PolicyName.of("CreditLimit");
    private static final PolicyName FRAUD = PolicyName.of("FraudCheck");
    private static final SemanticVersion CANDIDATE = SemanticVersion.parse("1.0.1");

    private final InMemoryDynamoDb dynamo = AwsFixtures.dynamo();
    private final DynamoDbRolloutStore store = new DynamoDbRolloutStore(dynamo, TABLES, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void keepsOpenCanariesInTheSparseActiveIndex() {
        Canary open = canary("c-1", CREDIT, NOW);
        Canary other = canary("c-2", FRAUD, NOW);
        store.save(open);
        store.save(other);
        other.cancel("manual", NOW.plusSeconds(10));
        store.save(other);

        assertEquals("ACTIVE", dynamo.stored(TABLES.canaries(), "CreditLimit", "c-1").get("gsiActiveStatus").s());
        assertNull(dynamo.stored(TABLES.canaries(), "FraudCheck", "c-2").get("gsiActiveStatus"));
        assertEquals(List.of("c-1"), store.listOpen().stream().map(Canary::id).toList());
        assertEquals(CanaryStatus.CANCELLED, store.findCanary(FRAUD, "c-2").orElseThrow().status());
    }

    @Test
    void ordersTheCanaryHistoryAndFindsTheOpenOne() {
        Canary old = canary("c-1", CREDIT, NOW);
        old.end("done", NOW.plusSeconds(5));
        store.save(old);
        store.save(canary("c-2", CREDIT, NOW.plusSeconds(60)));
        store.save(canary("c-3", FRAUD, NOW));

        assertEquals(List.of("c-2", "c-1"), store.canaryHistory(CREDIT).stream().map(Canary::id).toList());
        assertEquals("c-2", store.findOpen(CREDIT, ReadConsistency.CACHED).orElseThrow().id());
        assertTrue(store.hasOpenCanary(CREDIT));
        assertFalse(store.hasOpenCanary(PolicyName.of("Ghost")));
        assertEquals(Optional.empty(), store.findCanary(CREDIT, "ghost"));
    }

    @Test
    void tracksTheCurrentShadowWhileItIsLive() {
        Shadow paused = shadow("s-1", NOW);
        paused.pause(NOW.plusSeconds(1));
        store.save(paused);
        Shadow ended = shadow("s-0", NOW.minusSeconds(60));
        ended.end(NOW);
        store.save(ended);

        assertEquals("s-1", store.findCurrent(CREDIT, ReadConsistency.DIRECT).orElseThrow().id());
        assertEquals(ShadowStatus.PAUSED, store.findShadow(CREDIT, "s-1").orElseThrow().snapshot().status());
        assertEquals("ACTIVE", dynamo.stored(TABLES.shadows(), "CreditLimit", "s-1").get("gsiActiveStatus").s());
        assertNull(dynamo.stored(TABLES.shadows(), "CreditLimit", "s-0").get("gsiActiveStatus"));
        assertEquals(List.of("s-1", "s-0"), store.shadowHistory(CREDIT).stream().map(Shadow::id).toList());
        assertEquals(Optional.empty(), store.findShadow(CREDIT, "ghost"));
    }

    @Test
    void knowsWhichVersionsAreInUse() {
        store.save(canary("c-1", CREDIT, NOW));
        store.save(Shadow.start("s-1", FRAUD, SemanticVersion.parse("2.0.0"), 10, Duration.ofHours(1), List.of(), List.of(), "ana", NOW));

        assertTrue(store.isVersionInUse(CREDIT, CANDIDATE));
        assertFalse(store.isVersionInUse(CREDIT, SemanticVersion.FIRST));
        assertTrue(store.isVersionInUse(FRAUD, SemanticVersion.parse("2.0.0")));
        assertFalse(store.isVersionInUse(FRAUD, CANDIDATE));
    }

    private static Canary canary(String id, PolicyName policy, java.time.Instant startedAt) {
        return Canary.start(id, policy, CANDIDATE, SemanticVersion.FIRST, 10, null, null, Optional.empty(), "ana", startedAt);
    }

    private static Shadow shadow(String id, java.time.Instant startedAt) {
        return Shadow.start(id, CREDIT, CANDIDATE, 20, Duration.ofHours(4), List.of(), List.of(), "ana", startedAt);
    }
}
