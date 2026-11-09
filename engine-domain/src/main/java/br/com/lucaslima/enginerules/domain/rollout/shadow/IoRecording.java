package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import br.com.lucaslima.enginerules.domain.execution.IoBinding;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;

public final class IoRecording {

    private final IoBinding real;
    private final Map<String, List<ApiResponse>> recorded = new ConcurrentHashMap<>();

    public IoRecording(IoBinding real) {
        this.real = real;
    }

    public IoBinding binding() {
        return new IoBinding(new RecordingGateway(), new RecordingCache());
    }

    public RecordedIo recorded() {
        Map<String, List<ApiResponse>> copy = new ConcurrentHashMap<>();
        recorded.forEach((signature, responses) -> {
            synchronized (responses) {
                copy.put(signature, List.copyOf(responses));
            }
        });
        return new RecordedIo(copy);
    }

    private void remember(ApiRequest request, ApiResponse response) {
        List<ApiResponse> responses = recorded.computeIfAbsent(request.signature(), ignored -> new ArrayList<>());
        synchronized (responses) {
            responses.add(response);
        }
    }

    private final class RecordingGateway implements ApiGateway {
        @Override
        public ApiResponse call(ApiRequest request) {
            ApiResponse response = real.apiGateway().call(request);
            remember(request, response);
            return response;
        }
    }

    private final class RecordingCache implements ResultCache {
        @Override
        public Optional<ApiResponse> find(String signature, Duration maxAge) {
            Optional<ApiResponse> hit = real.resultCache().find(signature, maxAge);
            hit.ifPresent(response -> {
                List<ApiResponse> responses = recorded.computeIfAbsent(signature, ignored -> new ArrayList<>());
                synchronized (responses) {
                    responses.add(response);
                }
            });
            return hit;
        }

        @Override
        public void store(String signature, ApiResponse response) {
            real.resultCache().store(signature, response);
        }
    }
}
