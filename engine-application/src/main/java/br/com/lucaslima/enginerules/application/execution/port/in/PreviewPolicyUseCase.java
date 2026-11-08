package br.com.lucaslima.enginerules.application.execution.port.in;

import java.util.List;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.execution.ExecutionReport;

public interface PreviewPolicyUseCase {

    Result preview(Command command);

    record Command(PolicyDefinition definition, Map<String, Object> input) {
    }

    record Result(List<String> writeStates, ExecutionReport report) {
    }
}
