package br.com.lucaslima.enginerules.domain.catalog;

import java.util.Objects;
import java.util.Set;

public record Actor(String id, Set<String> groups) {

    public static final String OPERATORS_GROUP = "policy-operators";

    public Actor {
        Objects.requireNonNull(id, "id");
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }

    public boolean belongsTo(String group) {
        return groups.contains(group);
    }

    public boolean isOperator() {
        return belongsTo(OPERATORS_GROUP);
    }
}
