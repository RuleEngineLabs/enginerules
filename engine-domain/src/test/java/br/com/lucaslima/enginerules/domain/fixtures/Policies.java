package br.com.lucaslima.enginerules.domain.fixtures;

import java.util.List;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.definition.Transition;

public final class Policies {

    private Policies() {
    }

    public static PolicyDefinition policy(String entry, StateDefinition... states) {
        return new PolicyDefinition(entry, List.of(states));
    }

    public static StateDefinition.Builder execution(String id) {
        return StateDefinition.builder(id, "execution");
    }

    public static StateDefinition.Builder apiCall(String id) {
        return StateDefinition.builder(id, "apiCall");
    }

    public static StateDefinition.Builder parallel(String id) {
        return StateDefinition.builder(id, "parallel");
    }

    public static StateDefinition response(String id, int status, Object data) {
        return StateDefinition.builder(id, "response").status(status).data(data).build();
    }

    public static List<Transition> when(String condition, String to) {
        return List.of(new Transition(condition, to));
    }

    public static PolicyDefinition ageGate() {
        return policy("evaluate",
                execution("evaluate")
                        .map(Map.of("adult", "input.age >= 18"))
                        .contextKey("decision")
                        .transitions(when("decision.adult", "approved"))
                        .fallback("denied")
                        .build(),
                response("approved", 200, Map.of("approved", "true", "name", "input.name")),
                response("denied", 403, Map.of("approved", "false")));
    }
}
