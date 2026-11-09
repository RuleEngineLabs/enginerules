package br.com.lucaslima.enginerules.domain.catalog;

import java.util.Optional;

public record StableMove(SemanticVersion from, SemanticVersion to, Kind kind, Optional<SemanticVersion> discardedCandidate) {

    public enum Kind {
        PROMOTION,
        ROLLBACK
    }
}
