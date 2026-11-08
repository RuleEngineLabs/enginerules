package br.com.lucaslima.enginerules.application.rollout.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowView;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadCurrentShadowPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowStorePort;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionStatus;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;

public final class ShadowService implements ShadowUseCases {

    private final CatalogLookup catalog;
    private final LoadCurrentShadowPort currentShadows;
    private final ShadowStorePort shadows;
    private final RolloutAudit audit;
    private final Clock clock;

    public ShadowService(CatalogLookup catalog, LoadCurrentShadowPort currentShadows, ShadowStorePort shadows,
                         AppendAuditPort audit, Clock clock) {
        this.catalog = catalog;
        this.currentShadows = currentShadows;
        this.shadows = shadows;
        this.audit = new RolloutAudit(audit);
        this.clock = clock;
    }

    @Override
    public ShadowView start(StartCommand command) {
        PolicyName name = PolicyName.of(command.policy());
        Policy policy = catalog.requirePolicy(name, ReadConsistency.DIRECT);
        requireManageable(policy, command.actor());
        SemanticVersion target = SemanticVersion.parse(command.target());
        if (!policy.hasVersion(target) || policy.statusOf(target) == VersionStatus.REMOVED) {
            throw new RolloutException(RolloutErrorCode.INVALID_SHADOW, "target " + target + " is not a live version");
        }
        if (target.equals(policy.stable())) {
            throw new RolloutException(RolloutErrorCode.INVALID_SHADOW, "target must differ from the STABLE version");
        }
        Instant now = clock.instant();
        if (currentShadows.findCurrent(name, ReadConsistency.DIRECT).filter(shadow -> shadow.isLiveAt(now)).isPresent()) {
            throw new RolloutException(RolloutErrorCode.SHADOW_ACTIVE, "policy " + name + " already has a live shadow");
        }
        catalog.requireVersion(name, target);
        Shadow shadow = Shadow.start(UUID.randomUUID().toString(), name, target, command.percentage(), command.duration(),
                command.virtualRoutes(), command.ignoredPaths(), command.actor().id(), now);
        shadows.save(shadow);
        audit.shadow("shadow.started", shadow, command.actor().id(), now);
        return view(shadow, now);
    }

    @Override
    public ShadowView pause(String policy, String shadowId, Actor actor) {
        return change(policy, shadowId, actor, "shadow.paused", Shadow::pause);
    }

    @Override
    public ShadowView resume(String policy, String shadowId, Actor actor) {
        return change(policy, shadowId, actor, "shadow.resumed", Shadow::resume);
    }

    @Override
    public ShadowView end(String policy, String shadowId, Actor actor) {
        return change(policy, shadowId, actor, "shadow.ended", Shadow::end);
    }

    static ShadowView view(Shadow shadow, Instant now) {
        return new ShadowView(shadow.snapshot(), shadow.statusAt(now));
    }

    private ShadowView change(String policyName, String shadowId, Actor actor, String action, BiConsumer<Shadow, Instant> change) {
        PolicyName name = PolicyName.of(policyName);
        requireManageable(catalog.requirePolicy(name, ReadConsistency.DIRECT), actor);
        Shadow shadow = shadows.findShadow(name, shadowId)
                .orElseThrow(() -> new RolloutException(RolloutErrorCode.SHADOW_NOT_FOUND, "shadow " + shadowId + " not found"));
        Instant now = clock.instant();
        change.accept(shadow, now);
        shadows.save(shadow);
        audit.shadow(action, shadow, actor.id(), now);
        return view(shadow, now);
    }

    private static void requireManageable(Policy policy, Actor actor) {
        if (!policy.isManageableBy(actor)) {
            throw new CatalogException(CatalogErrorCode.NOT_OWNER,
                    actor.id() + " is neither owner of " + policy.name() + " nor operator");
        }
    }
}
