package br.com.lucaslima.enginerules.adapter.persistence.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.rollout.port.out.ExecutionSample;
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
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;

class FileSystemFailureModesTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final PolicyName NAME = PolicyName.of("CreditLimit");
    private static final byte[] INVALID_UTF8 = {(byte) 0xC3, (byte) 0x28};

    @Test
    void emptyStoresAnswerWithNothing() throws IOException {
        Path root = Files.createTempDirectory("empty");
        FileSystemRolloutStore rollouts = new FileSystemRolloutStore(root, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(0, new FileSystemPolicyStore(root).listPolicies(PolicyFilter.of(null, null, null), PageRequest.of(1, 5)).totalItems());
        assertEquals(List.of(), new FileSystemAuditLog(root).listAudits(AuditFilter.of(null, null, null), PageRequest.of(1, 5)).items());
        assertEquals(List.of(), rollouts.listOpen());
        assertEquals(List.of(), rollouts.canaryHistory(NAME));
        assertEquals(Optional.empty(), rollouts.findCanary(NAME, "ghost"));
        assertEquals(Optional.empty(), rollouts.findShadow(NAME, "ghost"));
        assertEquals(0, new FileSystemMetrics(root, 10).versionStats(NAME, SemanticVersion.FIRST, Route.STABLE, NOW, NOW).samples());
    }

    @Test
    void findsCanariesAndShadowsById() throws IOException {
        FileSystemRolloutStore rollouts = new FileSystemRolloutStore(Files.createTempDirectory("ids"), Clock.fixed(NOW, ZoneOffset.UTC));
        rollouts.save(Canary.start("c-1", NAME, SemanticVersion.parse("1.1.0"), SemanticVersion.FIRST, 10, null,
                CanaryCriteria.GLOBAL_DEFAULT, Optional.empty(), "ana", NOW));
        rollouts.save(Shadow.start("s-1", NAME, SemanticVersion.parse("1.1.0"), 10, null, List.of(), List.of(), "ana", NOW));

        assertEquals("c-1", rollouts.findCanary(NAME, "c-1").orElseThrow().id());
        assertEquals("s-1", rollouts.findShadow(NAME, "s-1").orElseThrow().id());
        assertEquals(1, rollouts.shadowHistory(NAME).size());
    }

    @Test
    void refusesToOverwriteAnImmutableVersion() throws IOException {
        Path root = Files.createTempDirectory("immutable");
        FileSystemPolicyStore store = new FileSystemPolicyStore(root);
        PolicyVersion original = PolicyVersion.of(NAME, SemanticVersion.FIRST, definition(200));
        store.savePolicy(Policy.create(NAME, "credit-team", Set.of(), original.hash(), new Actor("ana", Set.of("credit-team")), NOW),
                Optional.of(original));
        Policy loaded = store.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow();

        CatalogException failure = assertThrows(CatalogException.class, () ->
                store.savePolicy(loaded, Optional.of(PolicyVersion.of(NAME, SemanticVersion.FIRST, definition(201)))));

        assertEquals(CatalogErrorCode.CONCURRENT_MODIFICATION, failure.code());
        store.savePolicy(loaded, Optional.of(original));
        assertEquals(2, store.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow().revision());
    }

    @Test
    void surfacesUnreadableDocuments() throws IOException {
        Path root = Files.createTempDirectory("corrupt");
        Path policyFile = root.resolve("policies").resolve(NAME.value()).resolve("policy.json");
        Files.createDirectories(policyFile.getParent());
        Files.write(policyFile, INVALID_UTF8);
        Path auditFile = root.resolve("audit").resolve("audit.jsonl");
        Files.createDirectories(auditFile.getParent());
        Files.write(auditFile, INVALID_UTF8);
        Path metricsFile = root.resolve("metrics").resolve("executions").resolve(NAME.value() + ".jsonl");
        Files.createDirectories(metricsFile.getParent());
        Files.write(metricsFile, INVALID_UTF8);

        assertThrows(UncheckedIOException.class, () -> new FileSystemPolicyStore(root).loadPolicy(NAME, ReadConsistency.DIRECT));
        assertThrows(UncheckedIOException.class, () -> new FileSystemAuditLog(root).listAudits(AuditFilter.of(null, null, null), PageRequest.of(1, 5)));
        assertThrows(UncheckedIOException.class, () -> new FileSystemMetrics(root, 5).versionStats(NAME, SemanticVersion.FIRST, Route.STABLE, NOW, NOW));
        Files.writeString(policyFile, "[1]");
        assertThrows(IllegalStateException.class, () -> new FileSystemPolicyStore(root).loadPolicy(NAME, ReadConsistency.DIRECT));
    }

    @Test
    void surfacesUnwritableLocations() throws IOException {
        Path blocker = Files.createTempFile("blocker", ".txt");
        Path root = blocker.resolve("store");

        assertThrows(UncheckedIOException.class, () -> new FileSystemAuditLog(root).append(List.of(
                new AuditRecord(NOW, "CreditLimit", "ana", "x", Map.of()))));
        assertThrows(UncheckedIOException.class, () -> new StoreFiles(Files.createTempDirectory("lock").resolve(".lock"))
                .appendLine(root.resolve("file.txt"), "x"));
        assertThrows(UncheckedIOException.class, () -> new StoreFiles(Files.createTempDirectory("lock").resolve(".lock"))
                .writeAtomically(root.resolve("file.json"), Map.of()));
        FileSystemMetrics metrics = new FileSystemMetrics(root, 5);
        metrics.recordExecution(new ExecutionSample(NOW, NAME, SemanticVersion.FIRST, Route.STABLE, false, 1));
        assertThrows(UncheckedIOException.class, metrics::flush);
    }

    @Test
    void discardsSamplesBeyondTheBuffer() throws IOException {
        FileSystemMetrics metrics = new FileSystemMetrics(Files.createTempDirectory("buffer"), 1);

        metrics.recordExecution(new ExecutionSample(NOW, NAME, SemanticVersion.FIRST, Route.STABLE, false, 1));
        metrics.recordExecution(new ExecutionSample(NOW, NAME, SemanticVersion.FIRST, Route.STABLE, false, 1));

        assertEquals(1, metrics.discardedSamples());
        assertEquals(1, metrics.versionStats(NAME, SemanticVersion.FIRST, Route.STABLE, NOW, NOW).samples());
        assertTrue(metrics.shadowStats(NAME, "none").comparable() == 0);
    }

    private static PolicyDefinition definition(int status) {
        return new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(status).build()));
    }
}
