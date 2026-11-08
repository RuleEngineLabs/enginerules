package br.com.lucaslima.enginerules.domain.rollout.canary;

import java.time.Duration;
import java.time.Instant;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;

public record CanarySnapshot(
        String id,
        PolicyName policy,
        SemanticVersion candidate,
        SemanticVersion baseline,
        int weight,
        Duration duration,
        Duration extension,
        CanaryCriteria criteria,
        String shadowRef,
        CanaryStatus status,
        boolean awaitingExtension,
        String startedBy,
        Instant startedAt,
        Instant closedAt,
        String closingReason,
        long revision) {
}
