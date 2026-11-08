package br.com.lucaslima.enginerules.domain.fixtures;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;

public final class ScriptedApiGateway implements ApiGateway, ResultCache {

    private final Deque<ApiResponse> scripted = new ArrayDeque<>();
    private final List<ApiRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, ApiResponse> cache = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private Function<ApiRequest, ApiResponse> fallback = request -> ApiResponse.of(200, Map.of(), Map.of());
    private Duration latency = Duration.ZERO;

    public ScriptedApiGateway thenRespond(ApiResponse response) {
        scripted.add(response);
        return this;
    }

    public ScriptedApiGateway otherwise(Function<ApiRequest, ApiResponse> responder) {
        this.fallback = responder;
        return this;
    }

    public ScriptedApiGateway withLatency(Duration value) {
        this.latency = value;
        return this;
    }

    @Override
    public ApiResponse call(ApiRequest request) {
        requests.add(request);
        peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            if (!latency.isZero()) {
                Thread.sleep(latency);
            }
            synchronized (scripted) {
                ApiResponse next = scripted.poll();
                if (next != null) {
                    return next;
                }
            }
            return fallback.apply(request);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ApiResponse.failure("interrupted");
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public Optional<ApiResponse> find(String signature, Duration maxAge) {
        return Optional.ofNullable(cache.get(signature));
    }

    @Override
    public void store(String signature, ApiResponse response) {
        cache.put(signature, response);
    }

    public List<ApiRequest> requests() {
        return List.copyOf(requests);
    }

    public int peakInFlight() {
        return peakInFlight.get();
    }
}
