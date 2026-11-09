package br.com.lucaslima.enginerules.domain.catalog;

public sealed interface VersionSelector {

    static VersionSelector parse(String requested) {
        if (requested == null || requested.isBlank() || requested.trim().equalsIgnoreCase("stable")) {
            return new Stable();
        }
        if (!SemanticVersion.isWellFormed(requested)) {
            return new Unresolvable(requested);
        }
        return new Exact(SemanticVersion.parse(requested));
    }

    record Stable() implements VersionSelector {
    }

    record Exact(SemanticVersion version) implements VersionSelector {
    }

    record Unresolvable(String requested) implements VersionSelector {
    }
}
