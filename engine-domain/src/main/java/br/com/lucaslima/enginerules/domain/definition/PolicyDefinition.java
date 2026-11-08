package br.com.lucaslima.enginerules.domain.definition;

import java.util.List;
import java.util.Optional;

public record PolicyDefinition(String entry, List<StateDefinition> states) {

    public PolicyDefinition {
        states = OrderedCopies.listOrEmpty(states);
    }

    public Optional<StateDefinition> state(String id) {
        return states.stream().filter(state -> state.id() != null && state.id().equals(id)).findFirst();
    }
}
