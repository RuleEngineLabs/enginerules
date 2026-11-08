package br.com.lucaslima.enginerules.adapter.persistence.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;

class FileSystemPolicyStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));
    private static final PolicyName NAME = PolicyName.of("CreditLimit");
    private static final PolicyDefinition DEFINITION = new PolicyDefinition("done",
            List.of(StateDefinition.builder("done", "response").status(200).data(Map.of("ok", "true")).build()));

    @Test
    void persistsPolicyMetadataAndImmutableVersions() throws IOException {
        FileSystemPolicyStore store = new FileSystemPolicyStore(Files.createTempDirectory("store"));
        PolicyVersion content = PolicyVersion.of(NAME, SemanticVersion.FIRST, DEFINITION);
        Policy policy = Policy.create(NAME, "credit-team", Set.of(), content.hash(), ANA, NOW);

        store.savePolicy(policy, Optional.of(content));

        Policy loaded = store.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow();
        assertEquals(SemanticVersion.FIRST, loaded.stable());
        assertEquals(1, loaded.revision());
        assertEquals(content, store.loadVersion(NAME, SemanticVersion.FIRST).orElseThrow());
        assertEquals(1, store.listPolicies(PolicyFilter.of("Cred", null, null), PageRequest.of(1, 10)).totalItems());
    }

    @Test
    void rejectsSavingFromAStaleRevision() throws IOException {
        FileSystemPolicyStore store = new FileSystemPolicyStore(Files.createTempDirectory("store"));
        PolicyVersion content = PolicyVersion.of(NAME, SemanticVersion.FIRST, DEFINITION);
        store.savePolicy(Policy.create(NAME, "credit-team", Set.of(), content.hash(), ANA, NOW), Optional.of(content));
        Policy first = store.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow();
        Policy second = store.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow();
        store.savePolicy(first, Optional.empty());

        CatalogException failure = assertThrows(CatalogException.class, () -> store.savePolicy(second, Optional.empty()));

        assertEquals(CatalogErrorCode.CONCURRENT_MODIFICATION, failure.code());
    }

    @Test
    void appendsAndListsAuditRecordsNewestFirst() throws IOException {
        Path root = Files.createTempDirectory("audit");
        FileSystemAuditLog audit = new FileSystemAuditLog(root);
        audit.append(List.of(
                new AuditRecord(NOW, "CreditLimit", "ana", "policy.created", Map.of("version", "1.0.0")),
                new AuditRecord(NOW.plusSeconds(1), "CreditLimit", "ana", "version.published", Map.of("version", "1.1.0"))));

        List<AuditRecord> records = audit.listAudits(AuditFilter.of("CreditLimit", null, null), PageRequest.of(1, 10)).items();

        assertEquals("version.published", records.get(0).action());
        assertTrue(records.get(1).details().containsKey("version"));
    }
}
