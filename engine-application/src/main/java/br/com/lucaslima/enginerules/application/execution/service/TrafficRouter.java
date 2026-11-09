package br.com.lucaslima.enginerules.application.execution.service;

import java.util.Optional;
import java.util.random.RandomGenerator;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadOpenCanaryPort;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionSelector;
import br.com.lucaslima.enginerules.domain.catalog.VersionStatus;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;

public final class TrafficRouter {

    private final LoadOpenCanaryPort canaries;
    private final RandomGenerator random;

    public TrafficRouter(LoadOpenCanaryPort canaries, RandomGenerator random) {
        this.canaries = canaries;
        this.random = random;
    }

    public Resolution resolve(Policy policy, VersionSelector selector, Optional<String> consumerId,
                              ReadConsistency consistency) {
        SemanticVersion version = policy.resolveExecutable(selector, consumerId);
        if (!(selector instanceof VersionSelector.Stable)) {
            return new Resolution(version, Route.PINNED);
        }
        return canaries.findOpen(policy.name(), consistency)
                .filter(canary -> isRoutable(policy, canary))
                .filter(canary -> canary.route(consumerId, random) == Route.CANARY)
                .map(canary -> new Resolution(canary.candidate(), Route.CANARY))
                .orElse(new Resolution(version, Route.STABLE));
    }

    private static boolean isRoutable(Policy policy, Canary canary) {
        return policy.hasVersion(canary.candidate())
                && policy.statusOf(canary.candidate()) == VersionStatus.PENDING_CANDIDATE
                && canary.baseline().equals(policy.stable());
    }

    public record Resolution(SemanticVersion version, Route route) {
    }
}
