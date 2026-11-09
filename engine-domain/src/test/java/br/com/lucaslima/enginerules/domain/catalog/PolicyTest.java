package br.com.lucaslima.enginerules.domain.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));
    private static final Actor BRUNO = new Actor("bruno", Set.of("credit-team"));
    private static final Actor OUTSIDER = new Actor("eve", Set.of("marketing"));
    private static final Actor OPERATOR = new Actor("ops", Set.of(Actor.OPERATORS_GROUP));

    @Test
    void firstVersionIsBornStable() {
        Policy policy = newPolicy();

        assertEquals(SemanticVersion.FIRST, policy.stable());
        assertTrue(policy.pendingCandidate().isEmpty());
        assertInstanceOf(PolicyEvent.PolicyCreated.class, policy.drainEvents().get(0));
    }

    @Test
    void onlyOwnerGroupMembersCanCreate() {
        CatalogException failure = assertThrows(CatalogException.class, () ->
                Policy.create(PolicyName.of("CreditLimit"), "credit-team", Set.of(), hash("a"), OUTSIDER, NOW));

        assertEquals(CatalogErrorCode.NOT_OWNER, failure.code());
    }

    @Test
    void publishesACandidateUsingTheBumpOverTheLatestVersion() {
        Policy policy = newPolicy();

        Publication publication = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW);

        assertEquals(SemanticVersion.parse("1.1.0"), publication.version());
        assertTrue(publication.created());
        assertEquals(Optional.of(publication.version()), policy.pendingCandidate());
        assertEquals(VersionStatus.PENDING_CANDIDATE, policy.statusOf(publication.version()));
    }

    @Test
    void repeatingTheSamePublicationIsIdempotent() {
        Policy policy = newPolicy();
        policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.PATCH, NOW);

        Publication again = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.PATCH, NOW);

        assertFalse(again.created());
        assertEquals(SemanticVersion.parse("1.0.1"), again.version());
    }

    @Test
    void publishingContentEqualToStableReturnsStable() {
        Publication publication = newPolicy().publish(ANA, SemanticVersion.FIRST, hash("a"), VersionBump.PATCH, NOW);

        assertFalse(publication.created());
        assertEquals(SemanticVersion.FIRST, publication.version());
    }

    @Test
    void firstWinsWhileACandidateIsPending() {
        Policy policy = newPolicy();
        policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.PATCH, NOW);

        CatalogException failure = assertThrows(CatalogException.class, () ->
                policy.publish(BRUNO, SemanticVersion.FIRST, hash("c"), VersionBump.PATCH, NOW));

        assertEquals(CatalogErrorCode.PENDING_CANDIDATE_EXISTS, failure.code());
    }

    @Test
    void publicationMustBeBasedOnStable() {
        Policy policy = newPolicy();

        CatalogException failure = assertThrows(CatalogException.class, () ->
                policy.publish(ANA, SemanticVersion.parse("0.9.0"), hash("b"), VersionBump.PATCH, NOW));

        assertEquals(CatalogErrorCode.BASED_ON_NOT_STABLE, failure.code());
    }

    @Test
    void promotingTheCandidateMovesStable() {
        Policy policy = newPolicy();
        SemanticVersion candidate = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW).version();

        StableMove move = policy.moveStable(OPERATOR, candidate, NOW);

        assertEquals(StableMove.Kind.PROMOTION, move.kind());
        assertEquals(candidate, policy.stable());
        assertTrue(policy.pendingCandidate().isEmpty());
        assertEquals(2, policy.stableGeneration());
    }

    @Test
    void rollingBackTurnsTheFormerStableIntoTheOnlyCandidate() {
        Policy policy = newPolicy();
        SemanticVersion second = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW).version();
        policy.moveStable(ANA, second, NOW);
        SemanticVersion third = policy.publish(ANA, second, hash("c"), VersionBump.PATCH, NOW).version();

        StableMove move = policy.moveStable(ANA, SemanticVersion.FIRST, NOW);

        assertEquals(StableMove.Kind.ROLLBACK, move.kind());
        assertEquals(Optional.of(third), move.discardedCandidate());
        assertEquals(SemanticVersion.FIRST, policy.stable());
        assertEquals(Optional.of(second), policy.pendingCandidate());
        assertEquals(VersionStatus.REMOVED, policy.statusOf(third));
    }

    @Test
    void versionNumbersAreNeverReused() {
        Policy policy = newPolicy();
        SemanticVersion candidate = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW).version();
        policy.removeVersion(ANA, candidate, NOW);

        Publication next = policy.publish(ANA, SemanticVersion.FIRST, hash("c"), VersionBump.MINOR, NOW);

        assertEquals(SemanticVersion.parse("1.2.0"), next.version());
    }

    @Test
    void stableCannotBeRemoved() {
        CatalogException failure = assertThrows(CatalogException.class, () ->
                newPolicy().removeVersion(ANA, SemanticVersion.FIRST, NOW));

        assertEquals(CatalogErrorCode.VERSION_IS_STABLE, failure.code());
    }

    @Test
    void outsidersCannotMoveStable() {
        Policy policy = newPolicy();
        SemanticVersion candidate = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW).version();

        CatalogException failure = assertThrows(CatalogException.class, () -> policy.moveStable(OUTSIDER, candidate, NOW));

        assertEquals(CatalogErrorCode.NOT_OWNER, failure.code());
    }

    @Test
    void pendingCandidatesAreNotExecutableDirectly() {
        Policy policy = newPolicy();
        SemanticVersion candidate = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW).version();

        CatalogException failure = assertThrows(CatalogException.class, () ->
                policy.resolveExecutable(new VersionSelector.Exact(candidate), Optional.empty()));

        assertEquals(CatalogErrorCode.PENDING_CANDIDATE_NOT_EXECUTABLE, failure.code());
        assertEquals(SemanticVersion.FIRST, policy.resolveExecutable(VersionSelector.parse(null), Optional.empty()));
    }

    @Test
    void allowlistRestrictsConsumers() {
        Policy policy = Policy.create(PolicyName.of("CreditLimit"), "credit-team", Set.of("checkout"), hash("a"), ANA, NOW);

        assertEquals(SemanticVersion.FIRST, policy.resolveExecutable(VersionSelector.parse("stable"), Optional.of("checkout")));
        CatalogException failure = assertThrows(CatalogException.class, () ->
                policy.resolveExecutable(VersionSelector.parse("stable"), Optional.of("crm")));
        assertEquals(CatalogErrorCode.CONSUMER_NOT_ALLOWED, failure.code());
    }

    @Test
    void snapshotsRoundTrip() {
        Policy policy = newPolicy();
        policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW);

        Policy restored = Policy.restore(policy.snapshot());

        assertEquals(policy.snapshot(), restored.snapshot());
        assertEquals(List.of(), restored.drainEvents());
    }

    private static Policy newPolicy() {
        return Policy.create(PolicyName.of("CreditLimit"), "credit-team", Set.of(), hash("a"), ANA, NOW);
    }

    private static ContentHash hash(String seed) {
        return new ContentHash(seed.repeat(64).substring(0, 64));
    }
}
