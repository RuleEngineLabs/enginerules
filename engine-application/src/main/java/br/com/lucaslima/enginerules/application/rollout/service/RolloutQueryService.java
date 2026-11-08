package br.com.lucaslima.enginerules.application.rollout.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryView;
import br.com.lucaslima.enginerules.application.rollout.port.in.RolloutQueries;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowReport;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowView;
import br.com.lucaslima.enginerules.application.rollout.port.out.CanaryStorePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.QueryMetricsPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowStorePort;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;

public final class RolloutQueryService implements RolloutQueries {

    private final CanaryStorePort canaries;
    private final ShadowStorePort shadows;
    private final QueryMetricsPort metrics;
    private final Clock clock;

    public RolloutQueryService(CanaryStorePort canaries, ShadowStorePort shadows, QueryMetricsPort metrics, Clock clock) {
        this.canaries = canaries;
        this.shadows = shadows;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public List<CanaryView> canaries(String policy) {
        return canaries.canaryHistory(PolicyName.of(policy)).stream().map(CanaryView::of).toList();
    }

    @Override
    public List<ShadowView> shadows(String policy) {
        Instant now = clock.instant();
        return shadows.shadowHistory(PolicyName.of(policy)).stream().map(shadow -> ShadowService.view(shadow, now)).toList();
    }

    @Override
    public ShadowReport shadowReport(String policy, String shadowId) {
        PolicyName name = PolicyName.of(policy);
        Shadow shadow = shadows.findShadow(name, shadowId)
                .orElseThrow(() -> new RolloutException(RolloutErrorCode.SHADOW_NOT_FOUND, "shadow " + shadowId + " not found"));
        return new ShadowReport(ShadowService.view(shadow, clock.instant()), metrics.shadowStats(name, shadowId));
    }
}
