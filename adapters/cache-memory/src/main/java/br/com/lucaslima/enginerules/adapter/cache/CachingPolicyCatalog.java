package br.com.lucaslima.enginerules.adapter.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;

public final class CachingPolicyCatalog implements LoadPolicyPort {

    private final LoadPolicyPort source;
    private final Clock clock;
    private final Duration refreshInterval;
    private final Map<PolicyName, Cached> entries = new ConcurrentHashMap<>();

    public CachingPolicyCatalog(LoadPolicyPort source, Clock clock, Duration refreshInterval) {
        this.source = source;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
    }

    @Override
    public Optional<Policy> loadPolicy(PolicyName name, ReadConsistency consistency) {
        if (consistency == ReadConsistency.CACHED) {
            Cached cached = entries.get(name);
            if (cached != null && !isStale(cached)) {
                return Optional.of(Policy.restore(cached.policy().snapshot()));
            }
        }
        Optional<Policy> loaded = source.loadPolicy(name, ReadConsistency.DIRECT);
        loaded.ifPresentOrElse(policy -> remember(name, policy, consistency), () -> entries.remove(name));
        return loaded;
    }

    private void remember(PolicyName name, Policy policy, ReadConsistency consistency) {
        Cached fresh = new Cached(Policy.restore(policy.snapshot()), clock.instant());
        if (consistency == ReadConsistency.DIRECT) {
            entries.computeIfPresent(name, (key, current) -> newerGeneration(current, fresh));
        } else {
            entries.merge(name, fresh, CachingPolicyCatalog::newerGeneration);
        }
    }

    private static Cached newerGeneration(Cached current, Cached candidate) {
        return candidate.policy().stableGeneration() >= current.policy().stableGeneration() ? candidate : current;
    }

    private boolean isStale(Cached cached) {
        return Duration.between(cached.loadedAt(), clock.instant()).compareTo(refreshInterval) >= 0;
    }

    private record Cached(Policy policy, Instant loadedAt) {
    }
}
