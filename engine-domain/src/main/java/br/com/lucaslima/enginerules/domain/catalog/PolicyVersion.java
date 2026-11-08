package br.com.lucaslima.enginerules.domain.catalog;

import java.util.Objects;

import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;

public record PolicyVersion(PolicyName name, SemanticVersion version, PolicyDefinition definition, ContentHash hash) {

    public PolicyVersion {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(hash, "hash");
        if (!ContentHash.of(definition).equals(hash)) {
            throw new IllegalArgumentException("content hash does not match the definition of " + name + "@" + version);
        }
    }

    public static PolicyVersion of(PolicyName name, SemanticVersion version, PolicyDefinition definition) {
        return new PolicyVersion(name, version, definition, ContentHash.of(definition));
    }
}
