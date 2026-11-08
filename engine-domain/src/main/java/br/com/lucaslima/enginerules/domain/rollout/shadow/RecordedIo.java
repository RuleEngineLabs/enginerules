package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.util.List;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;

public record RecordedIo(Map<String, List<ApiResponse>> responsesBySignature) {

    public RecordedIo {
        responsesBySignature = Map.copyOf(responsesBySignature);
    }
}
