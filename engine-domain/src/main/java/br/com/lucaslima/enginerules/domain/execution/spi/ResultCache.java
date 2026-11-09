package br.com.lucaslima.enginerules.domain.execution.spi;

import java.time.Duration;
import java.util.Optional;

public interface ResultCache {

    Optional<ApiResponse> find(String signature, Duration maxAge);

    void store(String signature, ApiResponse response);

    static ResultCache disabled() {
        return new ResultCache() {
            @Override
            public Optional<ApiResponse> find(String signature, Duration maxAge) {
                return Optional.empty();
            }

            @Override
            public void store(String signature, ApiResponse response) {
            }
        };
    }
}
