package br.com.lucaslima.enginerules.application.execution.port.out;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;

public interface ExecutionTogglesPort {

    boolean traceEnabledFor(PolicyName name);
}
