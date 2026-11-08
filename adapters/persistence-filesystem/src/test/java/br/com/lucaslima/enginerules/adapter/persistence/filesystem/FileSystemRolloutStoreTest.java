package br.com.lucaslima.enginerules.adapter.persistence.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.rollout.port.out.ExecutionSample;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowSample;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.canary.VersionStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualRoute;

class FileSystemRolloutStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final PolicyName NAME = PolicyName.of("CreditLimit");
    private static final SemanticVersion CANDIDATE = SemanticVersion.parse("1.1.0");

    @Test
    void persistsCanariesAndShadowsAndGuardsTheirVersions() throws IOException {
        FileSystemRolloutStore store = new FileSystemRolloutStore(Files.createTempDirectory("rollouts"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        Canary canary = Canary.start("c-1", NAME, CANDIDATE, SemanticVersion.FIRST, 10, null, CanaryCriteria.GLOBAL_DEFAULT,
                Optional.of("s-1"), "ana", NOW);
        store.save(canary);
        store.save(Shadow.start("s-1", NAME, CANDIDATE, 50, null,
                List.of(new VirtualRoute("crm", "GET", "/x", 200, Map.of("ok", true))), List.of("data.at"), "ana", NOW));

        assertEquals(canary.snapshot().criteria(), store.findOpen(NAME, ReadConsistency.DIRECT).orElseThrow().criteria());
        assertEquals(1, store.listOpen().size());
        assertEquals(1, store.findCurrent(NAME, ReadConsistency.DIRECT).orElseThrow().virtualRoutes().size());
        assertTrue(store.isVersionInUse(NAME, CANDIDATE));
        assertFalse(store.isVersionInUse(NAME, SemanticVersion.FIRST));

        Canary loaded = store.findOpen(NAME, ReadConsistency.DIRECT).orElseThrow();
        loaded.cancel("test", NOW);
        store.save(loaded);
        assertFalse(store.hasOpenCanary(NAME));
    }

    @Test
    void aggregatesExecutionAndShadowMetrics() throws IOException {
        Path root = Files.createTempDirectory("metrics");
        FileSystemMetrics metrics = new FileSystemMetrics(root, 1_000);
        for (int index = 1; index <= 100; index++) {
            metrics.recordExecution(new ExecutionSample(NOW, NAME, CANDIDATE, Route.CANARY, index % 10 == 0, index * 10L));
        }
        metrics.recordExecution(new ExecutionSample(NOW, NAME, SemanticVersion.FIRST, Route.STABLE, false, 5));
        metrics.recordShadow(new ShadowSample(NOW, NAME, "s-1", ComparisonResult.EQUAL, 1.1));
        metrics.recordShadow(new ShadowSample(NOW, NAME, "s-1", ComparisonResult.DROPPED, 0));

        VersionStats stats = new FileSystemMetrics(root, 1).versionStats(NAME, CANDIDATE, Route.CANARY,
                NOW.minusSeconds(1), NOW.plusSeconds(1));
        metrics.flush();
        VersionStats flushed = new FileSystemMetrics(root, 1).versionStats(NAME, CANDIDATE, Route.CANARY,
                NOW.minusSeconds(1), NOW.plusSeconds(1));

        assertEquals(0, stats.samples());
        assertEquals(100, flushed.samples());
        assertEquals(10, flushed.errors());
        assertEquals(950, flushed.p95Micros());
        assertEquals(1L, metrics.shadowStats(NAME, "s-1").count(ComparisonResult.DROPPED));
    }
}
