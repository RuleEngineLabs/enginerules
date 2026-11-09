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

import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;

class CatalogValueObjectsTest {

    @Test
    void policyNamesAreCamelCaseAndNotReserved() {
        assertEquals("CreditLimit", PolicyName.of("CreditLimit").value());
        assertEquals("CreditLimit", PolicyName.of("CreditLimit").toString());
        assertTrue(PolicyName.of("Alpha").compareTo(PolicyName.of("Beta")) < 0);
        assertEquals(CatalogErrorCode.INVALID_POLICY_NAME,
                assertThrows(CatalogException.class, () -> PolicyName.of("credit-limit")).code());
        assertEquals(CatalogErrorCode.INVALID_POLICY_NAME, assertThrows(CatalogException.class, () -> PolicyName.of(null)).code());
        assertEquals(CatalogErrorCode.INVALID_POLICY_NAME, assertThrows(CatalogException.class, () -> PolicyName.of("Preview")).code());
    }

    @Test
    void semanticVersionsParseBumpAndOrder() {
        SemanticVersion version = SemanticVersion.parse("v1.2.3");

        assertEquals(new SemanticVersion(2, 0, 0), version.bump(VersionBump.MAJOR));
        assertEquals(new SemanticVersion(1, 3, 0), version.bump(VersionBump.MINOR));
        assertEquals(new SemanticVersion(1, 2, 4), version.bump(VersionBump.PATCH));
        assertEquals(version, version.bump(VersionBump.NONE));
        assertTrue(version.compareTo(SemanticVersion.FIRST) > 0);
        assertFalse(SemanticVersion.isWellFormed(null));
        assertEquals(CatalogErrorCode.INVALID_VERSION, assertThrows(CatalogException.class, () -> SemanticVersion.parse("1.x")).code());
        assertEquals(CatalogErrorCode.INVALID_VERSION, assertThrows(CatalogException.class, () -> SemanticVersion.parse(null)).code());
        assertEquals(CatalogErrorCode.INVALID_VERSION, assertThrows(CatalogException.class, () -> new SemanticVersion(-1, 0, 0)).code());
    }

    @Test
    void versionSelectorsDistinguishStableExactAndUnresolvable() {
        assertInstanceOf(VersionSelector.Stable.class, VersionSelector.parse(" STABLE "));
        assertInstanceOf(VersionSelector.Stable.class, VersionSelector.parse(""));
        assertEquals(SemanticVersion.parse("2.0.0"),
                assertInstanceOf(VersionSelector.Exact.class, VersionSelector.parse("2.0.0")).version());
        assertEquals("latest", assertInstanceOf(VersionSelector.Unresolvable.class, VersionSelector.parse("latest")).requested());
    }

    @Test
    void contentHashesAreSha256HexDigests() {
        ContentHash hash = new ContentHash("a".repeat(64));

        assertEquals("a".repeat(64), hash.toString());
        assertEquals("a".repeat(64), hash.value());
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("XYZ"));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash(null));
    }

    @Test
    void policyVersionsCarryTheHashOfTheirDefinition() {
        PolicyName name = PolicyName.of("Gate");
        PolicyDefinition approve = new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(200).build()));
        PolicyDefinition deny = new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(403).build()));

        PolicyVersion version = PolicyVersion.of(name, SemanticVersion.FIRST, approve);

        assertEquals(ContentHash.of(approve), version.hash());
        assertThrows(IllegalArgumentException.class, () -> new PolicyVersion(name, SemanticVersion.FIRST, deny, version.hash()));
        assertThrows(NullPointerException.class, () -> new PolicyVersion(null, SemanticVersion.FIRST, approve, version.hash()));
        assertThrows(NullPointerException.class, () -> new PolicyVersion(name, null, approve, version.hash()));
        assertThrows(NullPointerException.class, () -> new PolicyVersion(name, SemanticVersion.FIRST, null, version.hash()));
        assertThrows(NullPointerException.class, () -> new PolicyVersion(name, SemanticVersion.FIRST, approve, null));
    }

    @Test
    void actorsKnowTheirGroups() {
        Actor actor = new Actor("ana", null);

        assertTrue(actor.groups().isEmpty());
        assertFalse(actor.isOperator());
        assertTrue(new Actor("ops", Set.of(Actor.OPERATORS_GROUP)).isOperator());
    }

    @Test
    void versionEntriesExposeRemoval() {
        VersionEntry entry = new VersionEntry(SemanticVersion.FIRST, new ContentHash("b".repeat(64)), "ana",
                Instant.EPOCH, null);

        assertEquals(Optional.empty(), entry.removal());
        assertEquals(Optional.of(Instant.EPOCH), entry.removed(Instant.EPOCH).removal());
    }

    @Test
    void errorCodesHaveStableContractNames() {
        for (CatalogErrorCode code : CatalogErrorCode.values()) {
            assertEquals(code.name().toLowerCase(), code.code());
        }
        assertEquals("policy_not_found", new CatalogException(CatalogErrorCode.POLICY_NOT_FOUND, "x").code().code());
    }
}
