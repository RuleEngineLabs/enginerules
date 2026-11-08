package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.lucaslima.enginerules.domain.execution.IoBinding;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;

public final class VirtualIo implements ApiGateway {

    public static final String MISS = "virtual_io_miss";

    private final RecordedIo recorded;
    private final List<VirtualRoute> virtualRoutes;
    private final Map<String, AtomicInteger> replayCursor = new ConcurrentHashMap<>();
    private final AtomicInteger misses = new AtomicInteger();

    public VirtualIo(RecordedIo recorded, List<VirtualRoute> virtualRoutes) {
        this.recorded = recorded;
        this.virtualRoutes = List.copyOf(virtualRoutes);
    }

    public IoBinding binding() {
        return new IoBinding(this, ResultCache.disabled());
    }

    @Override
    public ApiResponse call(ApiRequest request) {
        List<ApiResponse> replay = recorded.responsesBySignature().get(request.signature());
        if (replay != null && !replay.isEmpty()) {
            int position = replayCursor.computeIfAbsent(request.signature(), ignored -> new AtomicInteger()).getAndIncrement();
            return replay.get(Math.min(position, replay.size() - 1));
        }
        return virtualRoutes.stream()
                .filter(route -> route.matches(request))
                .findFirst()
                .map(VirtualRoute::response)
                .orElseGet(() -> {
                    misses.incrementAndGet();
                    return ApiResponse.failure(MISS);
                });
    }

    public boolean missed() {
        return misses.get() > 0;
    }
}
