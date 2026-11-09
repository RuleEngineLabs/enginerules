package br.com.lucaslima.enginerules.adapter.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadCurrentShadowPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadOpenCanaryPort;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;

public final class CachingRolloutLookup implements LoadOpenCanaryPort, LoadCurrentShadowPort {

    private final LoadOpenCanaryPort canarySource;
    private final LoadCurrentShadowPort shadowSource;
    private final Clock clock;
    private final Duration refreshInterval;
    private final Map<PolicyName, Cached<Canary>> canaries = new ConcurrentHashMap<>();
    private final Map<PolicyName, Cached<Shadow>> shadows = new ConcurrentHashMap<>();

    public CachingRolloutLookup(LoadOpenCanaryPort canarySource, LoadCurrentShadowPort shadowSource, Clock clock,
                                Duration refreshInterval) {
        this.canarySource = canarySource;
        this.shadowSource = shadowSource;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
    }

    @Override
    public Optional<Canary> findOpen(PolicyName name, ReadConsistency consistency) {
        return lookup(canaries, name, consistency, policy -> canarySource.findOpen(policy, ReadConsistency.DIRECT))
                .map(canary -> Canary.restore(canary.snapshot()));
    }

    @Override
    public Optional<Shadow> findCurrent(PolicyName name, ReadConsistency consistency) {
        return lookup(shadows, name, consistency, policy -> shadowSource.findCurrent(policy, ReadConsistency.DIRECT))
                .map(shadow -> Shadow.restore(shadow.snapshot()));
    }

    private <T> Optional<T> lookup(Map<PolicyName, Cached<T>> cache, PolicyName name, ReadConsistency consistency,
                                   Function<PolicyName, Optional<T>> source) {
        Cached<T> cached = cache.get(name);
        Instant now = clock.instant();
        if (consistency == ReadConsistency.CACHED && cached != null
                && Duration.between(cached.loadedAt(), now).compareTo(refreshInterval) < 0) {
            return cached.value();
        }
        try {
            Optional<T> fresh = source.apply(name);
            cache.put(name, new Cached<>(fresh, now));
            return fresh;
        } catch (RuntimeException unavailable) {
            if (cached != null) {
                return cached.value();
            }
            return Optional.empty();
        }
    }

    private record Cached<T>(Optional<T> value, Instant loadedAt) {
    }
}
