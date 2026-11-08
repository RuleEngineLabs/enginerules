package br.com.lucaslima.enginerules.domain.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PolicyRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));
    private static final Actor OUTSIDER = new Actor("eve", Set.of("marketing"));

    @Test
    void exposesMetadata() {
        Policy policy = policy();

        assertEquals(PolicyName.of("CreditLimit"), policy.name());
        assertEquals("credit-team", policy.ownerGroup());
        assertEquals(0, policy.revision());
        assertEquals(NOW, policy.createdAt());
        assertEquals(NOW, policy.updatedAt());
        assertEquals(1, policy.versions().size());
        assertTrue(policy.hasVersion(SemanticVersion.FIRST));
        assertEquals(VersionStatus.STABLE, policy.statusOf(SemanticVersion.FIRST));
        assertEquals("ana", policy.entry(SemanticVersion.FIRST).author());
    }

    @Test
    void publishingRequiresBasedOnAndOwnership() {
        Policy policy = policy();

        assertEquals(CatalogErrorCode.BASED_ON_REQUIRED, assertThrows(CatalogException.class, () ->
                policy.publish(ANA, null, hash("b"), VersionBump.PATCH, NOW)).code());
        assertEquals(CatalogErrorCode.NOT_OWNER, assertThrows(CatalogException.class, () ->
                policy.publish(OUTSIDER, SemanticVersion.FIRST, hash("b"), VersionBump.PATCH, NOW)).code());
        assertEquals(CatalogErrorCode.NOT_OWNER, assertThrows(CatalogException.class, () ->
                Policy.create(PolicyName.of("Other"), " ", Set.of(), hash("a"), ANA, NOW)).code());
        assertEquals(CatalogErrorCode.NOT_OWNER, assertThrows(CatalogException.class, () ->
                Policy.create(PolicyName.of("Other"), null, Set.of(), hash("a"), ANA, NOW)).code());
    }

    @Test
    void noChangeBumpPublishesAsPatch() {
        Policy policy = policy();

        assertEquals(SemanticVersion.parse("1.0.1"), policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.NONE, NOW).version());
    }

    @Test
    void movingStableValidatesTheTarget() {
        Policy policy = policy();
        SemanticVersion candidate = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW).version();

        assertEquals(CatalogErrorCode.ALREADY_STABLE, assertThrows(CatalogException.class, () ->
                policy.moveStable(ANA, SemanticVersion.FIRST, NOW)).code());
        assertEquals(CatalogErrorCode.VERSION_NOT_FOUND, assertThrows(CatalogException.class, () ->
                policy.moveStable(ANA, SemanticVersion.parse("9.0.0"), NOW)).code());
        policy.removeVersion(ANA, candidate, NOW);
        assertEquals(CatalogErrorCode.VERSION_REMOVED, assertThrows(CatalogException.class, () ->
                policy.moveStable(ANA, candidate, NOW)).code());
        assertEquals(CatalogErrorCode.VERSION_REMOVED, assertThrows(CatalogException.class, () ->
                policy.removeVersion(ANA, candidate, NOW)).code());
        assertEquals(CatalogErrorCode.VERSION_REMOVED, assertThrows(CatalogException.class, () ->
                policy.resolveExecutable(new VersionSelector.Exact(candidate), Optional.empty())).code());
    }

    @Test
    void retiredVersionsStayExecutableByPin() {
        Policy policy = policy();
        SemanticVersion candidate = policy.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, NOW).version();
        policy.moveStable(ANA, candidate, NOW);

        assertEquals(VersionStatus.RETIRED, policy.statusOf(SemanticVersion.FIRST));
        assertEquals(SemanticVersion.FIRST, policy.resolveExecutable(new VersionSelector.Exact(SemanticVersion.FIRST), Optional.empty()));
        assertEquals(CatalogErrorCode.VERSION_NOT_FOUND, assertThrows(CatalogException.class, () ->
                policy.resolveExecutable(VersionSelector.parse("abc"), Optional.empty())).code());
    }

    @Test
    void skipsVersionNumbersAlreadyIssued() {
        PolicySnapshot snapshot = new PolicySnapshot(PolicyName.of("CreditLimit"), "credit-team", Set.of(), Set.of(),
                SemanticVersion.FIRST, null, 1, List.of(
                        entry("1.0.0", "a", null),
                        entry("1.1.0", "b", NOW),
                        entry("1.2.0", "c", NOW)),
                NOW, NOW, 3);
        Policy policy = Policy.restore(snapshot);

        assertEquals(SemanticVersion.parse("1.3.0"), policy.publish(ANA, SemanticVersion.FIRST, hash("d"), VersionBump.MINOR, NOW).version());
    }

    @Test
    void restoringRejectsBrokenInvariants() {
        assertThrows(IllegalStateException.class, () -> Policy.restore(new PolicySnapshot(PolicyName.of("CreditLimit"),
                "credit-team", Set.of(), Set.of(), SemanticVersion.FIRST, null, 1, List.of(entry("1.0.0", "a", NOW)),
                NOW, NOW, 1)));
        assertThrows(IllegalStateException.class, () -> Policy.restore(new PolicySnapshot(PolicyName.of("CreditLimit"),
                "credit-team", Set.of(), Set.of(), SemanticVersion.FIRST, SemanticVersion.FIRST, 1,
                List.of(entry("1.0.0", "a", null)), NOW, NOW, 1)));
    }

    private static VersionEntry entry(String version, String seed, Instant removedAt) {
        return new VersionEntry(SemanticVersion.parse(version), hash(seed), "ana", NOW, removedAt);
    }

    private static Policy policy() {
        return Policy.create(PolicyName.of("CreditLimit"), "credit-team", Set.of(), hash("a"), ANA, NOW);
    }

    private static ContentHash hash(String seed) {
        return new ContentHash(seed.repeat(64).substring(0, 64));
    }
}
