package br.com.lucaslima.enginerules.adapter.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;

public final class InMemoryResultCache implements ResultCache {

    private final Clock clock;
    private final Duration retention;
    private final Map<String, Stored> entries = new ConcurrentHashMap<>();

    public InMemoryResultCache(Clock clock, Duration retention) {
        this.clock = clock;
        this.retention = retention;
    }

    @Override
    public Optional<ApiResponse> find(String signature, Duration maxAge) {
        Stored stored = entries.get(signature);
        if (stored == null) {
            return Optional.empty();
        }
        Duration age = Duration.between(stored.storedAt(), clock.instant());
        if (age.compareTo(retention) > 0) {
            entries.remove(signature, stored);
            return Optional.empty();
        }
        return age.compareTo(maxAge) <= 0 ? Optional.of(stored.response()) : Optional.empty();
    }

    @Override
    public void store(String signature, ApiResponse response) {
        entries.put(signature, new Stored(response, clock.instant()));
    }

    private record Stored(ApiResponse response, Instant storedAt) {
    }
}
