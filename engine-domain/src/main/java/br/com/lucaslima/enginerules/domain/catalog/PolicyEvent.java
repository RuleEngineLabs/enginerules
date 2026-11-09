package br.com.lucaslima.enginerules.domain.catalog;

import java.time.Instant;
import java.util.Optional;

public sealed interface PolicyEvent {

    PolicyName policy();

    String actor();

    Instant occurredAt();

    record PolicyCreated(PolicyName policy, String owner, SemanticVersion version, String actor, Instant occurredAt)
            implements PolicyEvent {
    }

    record VersionPublished(PolicyName policy, SemanticVersion version, SemanticVersion basedOn, VersionBump bump,
                            String actor, Instant occurredAt) implements PolicyEvent {
    }

    record StableVersionMoved(PolicyName policy, SemanticVersion from, SemanticVersion to, StableMove.Kind kind,
                              Optional<SemanticVersion> discardedCandidate, String actor, Instant occurredAt)
            implements PolicyEvent {
    }

    record VersionRemoved(PolicyName policy, SemanticVersion version, String actor, Instant occurredAt)
            implements PolicyEvent {
    }
}
