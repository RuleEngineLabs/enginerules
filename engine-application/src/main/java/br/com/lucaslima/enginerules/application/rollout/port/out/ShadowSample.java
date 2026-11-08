package br.com.lucaslima.enginerules.application.rollout.port.out;

import java.time.Instant;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;

public record ShadowSample(Instant at, PolicyName policy, String shadowId, ComparisonResult result, double latencyRatio) {
}
