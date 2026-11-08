package br.com.lucaslima.enginerules.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.RolloutGuardPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.CanaryStorePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ExecutionSample;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadCurrentShadowPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadOpenCanaryPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.QueryMetricsPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.RecordMetricsPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowQueuePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowSample;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowStorePort;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanarySnapshot;
import br.com.lucaslima.enginerules.domain.rollout.canary.VersionStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowSnapshot;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;

final class InMemoryRollouts implements LoadOpenCanaryPort, CanaryStorePort, LoadCurrentShadowPort, ShadowStorePort,
        RolloutGuardPort, RecordMetricsPort, QueryMetricsPort, ShadowQueuePort {

    private final Map<String, CanarySnapshot> canaries = new ConcurrentHashMap<>();
    private final Map<String, ShadowSnapshot> shadows = new ConcurrentHashMap<>();
    private final List<ExecutionSample> executions = new ArrayList<>();
    private final List<ShadowSample> shadowSamples = new ArrayList<>();
    private boolean queueAccepts = true;

    @Override
    public Optional<Canary> findOpen(PolicyName name, ReadConsistency consistency) {
        return canaryHistory(name).stream().filter(canary -> canary.status().isOpen()).findFirst();
    }

    @Override
    public Optional<Canary> findCanary(PolicyName name, String id) {
        return Optional.ofNullable(canaries.get(id)).map(Canary::restore);
    }

    @Override
    public List<Canary> canaryHistory(PolicyName name) {
        return canaries.values().stream().filter(snapshot -> snapshot.policy().equals(name)).map(Canary::restore)
                .sorted(Comparator.comparing(Canary::startedAt).reversed()).toList();
    }

    @Override
    public List<Canary> listOpen() {
        return canaries.values().stream().map(Canary::restore).filter(canary -> canary.status().isOpen()).toList();
    }

    @Override
    public void save(Canary canary) {
        canaries.put(canary.id(), canary.snapshot());
    }

    @Override
    public Optional<Shadow> findCurrent(PolicyName name, ReadConsistency consistency) {
        return shadowHistory(name).stream()
                .filter(shadow -> shadow.snapshot().status() == ShadowStatus.ACTIVE || shadow.snapshot().status() == ShadowStatus.PAUSED)
                .findFirst();
    }

    @Override
    public Optional<Shadow> findShadow(PolicyName name, String id) {
        return Optional.ofNullable(shadows.get(id)).map(Shadow::restore);
    }

    @Override
    public List<Shadow> shadowHistory(PolicyName name) {
        return shadows.values().stream().filter(snapshot -> snapshot.policy().equals(name)).map(Shadow::restore).toList();
    }

    @Override
    public void save(Shadow shadow) {
        shadows.put(shadow.id(), shadow.snapshot());
    }

    @Override
    public boolean hasOpenCanary(PolicyName name) {
        return findOpen(name, ReadConsistency.DIRECT).isPresent();
    }

    @Override
    public boolean isVersionInUse(PolicyName name, SemanticVersion version) {
        return findOpen(name, ReadConsistency.DIRECT).map(canary -> canary.candidate().equals(version)).orElse(false)
                || findCurrent(name, ReadConsistency.DIRECT).map(shadow -> shadow.target().equals(version)).orElse(false);
    }

    @Override
    public synchronized void recordExecution(ExecutionSample sample) {
        executions.add(sample);
    }

    @Override
    public synchronized void recordShadow(ShadowSample sample) {
        shadowSamples.add(sample);
    }

    @Override
    public synchronized VersionStats versionStats(PolicyName policy, SemanticVersion version, Route route, Instant from, Instant to) {
        List<ExecutionSample> matching = executions.stream()
                .filter(sample -> sample.policy().equals(policy) && sample.version().equals(version) && sample.route() == route)
                .toList();
        long errors = matching.stream().filter(ExecutionSample::error).count();
        return new VersionStats(matching.size(), errors, 1_000, 2_000);
    }

    @Override
    public synchronized ShadowStats shadowStats(PolicyName policy, String shadowId) {
        Map<ComparisonResult, Long> counts = new EnumMap<>(ComparisonResult.class);
        shadowSamples.stream().filter(sample -> sample.shadowId().equals(shadowId))
                .forEach(sample -> counts.merge(sample.result(), 1L, Long::sum));
        return new ShadowStats(counts, 1, 1);
    }

    @Override
    public boolean offer(Runnable shadowExecution) {
        if (queueAccepts) {
            shadowExecution.run();
        }
        return queueAccepts;
    }

    void rejectShadowCopies() {
        queueAccepts = false;
    }

    synchronized List<ExecutionSample> executions() {
        return List.copyOf(executions);
    }
}
