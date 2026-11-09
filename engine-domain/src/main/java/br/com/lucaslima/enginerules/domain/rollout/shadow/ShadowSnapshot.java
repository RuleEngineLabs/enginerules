package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.time.Instant;
import java.util.List;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;

public record ShadowSnapshot(
        String id,
        PolicyName policy,
        SemanticVersion target,
        int percentage,
        List<VirtualRoute> virtualRoutes,
        List<String> ignoredPaths,
        ShadowStatus status,
        String startedBy,
        Instant startedAt,
        Instant expiresAt,
        Instant endedAt,
        long revision) {

    public ShadowSnapshot {
        virtualRoutes = List.copyOf(virtualRoutes);
        ignoredPaths = List.copyOf(ignoredPaths);
    }
}
