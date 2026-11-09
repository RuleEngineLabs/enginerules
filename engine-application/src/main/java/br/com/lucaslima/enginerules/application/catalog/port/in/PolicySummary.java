package br.com.lucaslima.enginerules.application.catalog.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.VersionStatus;

public record PolicySummary(
        String name,
        String ownerGroup,
        String stableVersion,
        Optional<String> pendingCandidate,
        Instant createdAt,
        Instant updatedAt,
        List<VersionSummary> versions) {

    public PolicySummary {
        versions = List.copyOf(versions);
    }

    public static PolicySummary of(Policy policy) {
        List<VersionSummary> versions = policy.versions().stream()
                .map(entry -> new VersionSummary(entry.version().toString(), policy.statusOf(entry.version()),
                        entry.hash().value(), entry.author(), entry.createdAt(), entry.removal()))
                .toList();
        return new PolicySummary(policy.name().value(), policy.ownerGroup(), policy.stable().toString(),
                policy.pendingCandidate().map(Object::toString), policy.createdAt(), policy.updatedAt(), versions);
    }

    public record VersionSummary(String version, VersionStatus status, String hash, String author, Instant createdAt,
                                 Optional<Instant> removedAt) {
    }
}
