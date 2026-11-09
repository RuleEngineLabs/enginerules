package br.com.lucaslima.enginerules.application.execution.port.in;

import java.util.Map;
import java.util.Optional;

import br.com.lucaslima.enginerules.domain.execution.ExecutionReport;

public interface ExecutePolicyUseCase {

    Result execute(Command command);

    record Command(String name, String requestedVersion, Map<String, Object> input, Optional<String> consumerId,
                   boolean noCache) {
    }

    record Result(String name, String version, String route, ExecutionReport report) {
    }
}
