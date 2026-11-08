package br.com.lucaslima.enginerules.domain.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record PolicySnapshot(
        PolicyName name,
        String ownerGroup,
        Set<String> approvers,
        Set<String> allowedConsumers,
        SemanticVersion stable,
        SemanticVersion pendingCandidate,
        long stableGeneration,
        List<VersionEntry> versions,
        Instant createdAt,
        Instant updatedAt,
        long revision) {

    public PolicySnapshot {
        approvers = Set.copyOf(approvers);
        allowedConsumers = Set.copyOf(allowedConsumers);
        versions = List.copyOf(versions);
    }
}
