package br.com.lucaslima.enginerules.domain.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateKind;
import br.com.lucaslima.enginerules.domain.definition.Transition;

public final class ChangeClassifier {

    public VersionBump classify(PolicyDefinition previous, PolicyDefinition next) {
        if (ContentHash.of(previous).equals(ContentHash.of(next))) {
            return VersionBump.NONE;
        }
        if (!responseContract(previous).equals(responseContract(next))) {
            return VersionBump.MAJOR;
        }
        if (!topology(previous).equals(topology(next))) {
            return VersionBump.MINOR;
        }
        return VersionBump.PATCH;
    }

    private static Map<String, Integer> responseContract(PolicyDefinition definition) {
        return definition.states().stream()
                .filter(state -> StateKind.RESPONSE.contractName().equals(state.kind()))
                .collect(Collectors.toMap(StateDefinition::id, state -> Objects.requireNonNullElse(state.status(), 0),
                        (first, second) -> first));
    }

    private static Map<String, List<String>> topology(PolicyDefinition definition) {
        Map<String, List<String>> edges = definition.states().stream()
                .collect(Collectors.toMap(StateDefinition::id, ChangeClassifier::edgesOf, (first, second) -> first));
        edges.put("<entry>", List.of(String.valueOf(definition.entry())));
        return edges;
    }

    private static List<String> edgesOf(StateDefinition state) {
        List<String> edges = new ArrayList<>();
        edges.add("kind:" + state.kind());
        state.transitionsOrEmpty().stream().map(Transition::to).forEach(target -> edges.add("to:" + target));
        edges.add("fallback:" + state.fallback());
        edges.add("each:" + state.each());
        return edges;
    }
}
