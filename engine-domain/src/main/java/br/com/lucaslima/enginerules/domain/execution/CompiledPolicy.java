package br.com.lucaslima.enginerules.domain.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CompiledPolicy(String entry, Map<String, CompiledState> states, List<String> writeStates) {

    public CompiledPolicy {
        states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
        writeStates = List.copyOf(writeStates);
    }

    public CompiledState state(String id) {
        CompiledState state = states.get(id);
        if (state == null) {
            throw new IllegalStateException("compiled policy has no state '" + id + "'");
        }
        return state;
    }
}
