package br.com.lucaslima.enginerules.application.rollout.port.out;

import java.time.Instant;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.Route;

public record ExecutionSample(Instant at, PolicyName policy, SemanticVersion version, Route route, boolean error,
                              long durationMicros) {
}
