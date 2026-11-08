package br.com.lucaslima.enginerules.adapter.persistence.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicySnapshot;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionBump;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanarySnapshot;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowSnapshot;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualRoute;

class DocumentsRoundTripTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final PolicyName NAME = PolicyName.of("CreditLimit");
    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));

    @Test
    void roundTripsPolicySnapshotsWithPendingAndRemovedVersions() {
        Policy policy = Policy.create(NAME, "credit-team", Set.of("checkout", "app"), hash(200), ANA, NOW);
        policy.publish(ANA, SemanticVersion.FIRST, hash(201), VersionBump.PATCH, NOW);
        policy.removeVersion(ANA, SemanticVersion.parse("1.0.1"), NOW.plusSeconds(5));
        policy.publish(ANA, SemanticVersion.FIRST, hash(202), VersionBump.PATCH, NOW.plusSeconds(9));

        Map<String, Object> tree = PolicyDocuments.fromSnapshot(policy.snapshot(), 7);
        PolicySnapshot restored = PolicyDocuments.toSnapshot(tree);

        assertEquals(7L, restored.revision());
        assertEquals(List.of("app", "checkout"), tree.get("allowedConsumers"));
        assertEquals(policy.snapshot().versions(), restored.versions());
        assertEquals(SemanticVersion.parse("1.0.2"), restored.pendingCandidate());
        assertEquals(policy.snapshot().allowedConsumers(), restored.allowedConsumers());
    }

    @Test
    void roundTripsPolicySnapshotsWithoutOptionalFields() {
        Policy policy = Policy.create(NAME, "credit-team", Set.of(), hash(200), ANA, NOW);
        Map<String, Object> tree = PolicyDocuments.fromSnapshot(policy.snapshot(), 1);
        tree.remove("versions");
        tree.remove("approvers");

        PolicySnapshot restored = PolicyDocuments.toSnapshot(tree);

        assertNull(restored.pendingCandidate());
        assertEquals(List.of(), restored.versions());
        assertEquals(Set.of(), restored.approvers());
    }

    @Test
    void roundTripsImmutableVersions() {
        PolicyVersion version = PolicyVersion.of(NAME, SemanticVersion.parse("1.2.3"), definition(200));

        assertEquals(version, PolicyDocuments.toVersion(PolicyDocuments.fromVersion(version)));
    }

    @Test
    void roundTripsCanariesOpenAndClosed() {
        Canary open = Canary.start("c-1", NAME, SemanticVersion.parse("1.0.1"), SemanticVersion.FIRST, 15,
                Duration.ofHours(2), new CanaryCriteria(0.5, 1.2, 50, 0.0), Optional.of("s-1"), "ana", NOW);
        Canary closed = Canary.start("c-2", NAME, SemanticVersion.parse("1.0.1"), SemanticVersion.FIRST, 15,
                null, null, Optional.empty(), "ana", NOW);
        closed.cancel("manual", NOW.plusSeconds(60));

        CanarySnapshot reopened = RolloutDocuments.toCanary(RolloutDocuments.fromCanary(open.snapshot(), 3));
        CanarySnapshot reclosed = RolloutDocuments.toCanary(RolloutDocuments.fromCanary(closed.snapshot(), 4));

        assertEquals(withRevision(open.snapshot(), 3), reopened);
        assertEquals(withRevision(closed.snapshot(), 4), reclosed);
        assertEquals(NOW.plusSeconds(60), reclosed.closedAt());
    }

    @Test
    void roundTripsShadowsWithRoutesAndIgnoredPaths() {
        Shadow shadow = Shadow.start("s-1", NAME, SemanticVersion.parse("1.1.0"), 25, Duration.ofHours(3),
                List.of(new VirtualRoute("crm", "GET", "/customers/{id}", 200, Map.of("tier", "gold"))),
                List.of("data.timestamp"), "ana", NOW);
        shadow.end(NOW.plusSeconds(30));
        Map<String, Object> tree = RolloutDocuments.fromShadow(shadow.snapshot(), 2);

        ShadowSnapshot restored = RolloutDocuments.toShadow(tree);
        tree.remove("virtualRoutes");
        tree.remove("ignoredPaths");

        assertEquals(shadow.snapshot().virtualRoutes(), restored.virtualRoutes());
        assertEquals(List.of("data.timestamp"), restored.ignoredPaths());
        assertEquals(NOW.plusSeconds(30), restored.endedAt());
        assertEquals(2L, restored.revision());
        assertEquals(List.of(), RolloutDocuments.toShadow(tree).virtualRoutes());
    }

    @Test
    void roundTripsAuditRecords() {
        AuditRecord record = new AuditRecord(NOW, "CreditLimit", "ana", "stable.moved", Map.of("from", "1.0.0", "to", "1.0.1"));
        Map<String, Object> withoutDetails = AuditDocuments.fromRecord(record);
        withoutDetails.remove("details");

        assertEquals(record, AuditDocuments.toRecord(AuditDocuments.fromRecord(record)));
        assertEquals(Map.of(), AuditDocuments.toRecord(withoutDetails).details());
    }

    @Test
    void rejectsCorruptedDocuments() {
        assertThrows(IllegalStateException.class, () -> PolicyDocuments.object("not an object"));
        assertEquals(Map.of("a", 1), PolicyDocuments.object(Map.of("a", 1)));
    }

    private static CanarySnapshot withRevision(CanarySnapshot snapshot, long revision) {
        return new CanarySnapshot(snapshot.id(), snapshot.policy(), snapshot.candidate(), snapshot.baseline(), snapshot.weight(),
                snapshot.duration(), snapshot.extension(), snapshot.criteria(), snapshot.shadowRef(), snapshot.status(),
                snapshot.awaitingExtension(), snapshot.startedBy(), snapshot.startedAt(), snapshot.closedAt(),
                snapshot.closingReason(), revision);
    }

    private static ContentHash hash(int status) {
        return ContentHash.of(definition(status));
    }

    private static PolicyDefinition definition(int status) {
        return new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(status).build()));
    }
}
