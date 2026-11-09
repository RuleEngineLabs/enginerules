package br.com.lucaslima.enginerules.domain.catalog;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record VersionEntry(
        SemanticVersion version,
        ContentHash hash,
        String author,
        Instant createdAt,
        Instant removedAt) {

    public VersionEntry {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isRemoved() {
        return removedAt != null;
    }

    public Optional<Instant> removal() {
        return Optional.ofNullable(removedAt);
    }

    VersionEntry removed(Instant at) {
        return new VersionEntry(version, hash, author, createdAt, at);
    }
}
