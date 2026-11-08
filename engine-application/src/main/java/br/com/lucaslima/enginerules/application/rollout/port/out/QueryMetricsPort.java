package br.com.lucaslima.enginerules.application.rollout.port.out;

import java.time.Instant;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.VersionStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;

public interface QueryMetricsPort {

    VersionStats versionStats(PolicyName policy, SemanticVersion version, Route route, Instant from, Instant to);

    ShadowStats shadowStats(PolicyName policy, String shadowId);
}
