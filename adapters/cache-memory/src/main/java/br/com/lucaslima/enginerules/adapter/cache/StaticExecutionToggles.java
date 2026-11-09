package br.com.lucaslima.enginerules.adapter.cache;

import java.util.Set;

import br.com.lucaslima.enginerules.application.execution.port.out.ExecutionTogglesPort;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;

public final class StaticExecutionToggles implements ExecutionTogglesPort {

    private final boolean traceEnabledGlobally;
    private final Set<String> tracedPolicies;

    public StaticExecutionToggles(boolean traceEnabledGlobally, Set<String> tracedPolicies) {
        this.traceEnabledGlobally = traceEnabledGlobally;
        this.tracedPolicies = Set.copyOf(tracedPolicies);
    }

    @Override
    public boolean traceEnabledFor(PolicyName name) {
        return traceEnabledGlobally || tracedPolicies.contains(name.value());
    }
}
