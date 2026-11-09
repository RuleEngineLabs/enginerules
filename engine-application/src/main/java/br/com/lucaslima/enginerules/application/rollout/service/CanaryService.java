package br.com.lucaslima.enginerules.application.rollout.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryView;
import br.com.lucaslima.enginerules.application.rollout.port.out.CanaryStorePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadOpenCanaryPort;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.ChangeClassifier;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionBump;
import br.com.lucaslima.enginerules.domain.catalog.VersionStatus;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;

public final class CanaryService implements CanaryUseCases {

    private final CatalogLookup catalog;
    private final LoadOpenCanaryPort openCanaries;
    private final CanaryStorePort canaries;
    private final ChangeClassifier changeClassifier;
    private final CanaryCriteria globalCriteria;
    private final RolloutAudit audit;
    private final Clock clock;

    public CanaryService(CatalogLookup catalog, LoadOpenCanaryPort openCanaries, CanaryStorePort canaries,
                         ChangeClassifier changeClassifier, CanaryCriteria globalCriteria, AppendAuditPort audit,
                         Clock clock) {
        this.catalog = catalog;
        this.openCanaries = openCanaries;
        this.canaries = canaries;
        this.changeClassifier = changeClassifier;
        this.globalCriteria = globalCriteria;
        this.audit = new RolloutAudit(audit);
        this.clock = clock;
    }

    @Override
    public CanaryView start(StartCommand command) {
        PolicyName name = PolicyName.of(command.policy());
        Policy policy = catalog.requirePolicy(name, ReadConsistency.DIRECT);
        requireManageable(policy, command.actor());
        SemanticVersion candidate = SemanticVersion.parse(command.candidate());
        if (!policy.hasVersion(candidate) || policy.statusOf(candidate) != VersionStatus.PENDING_CANDIDATE) {
            throw new RolloutException(RolloutErrorCode.CANDIDATE_NOT_PENDING,
                    "only the pending candidate can enter a canary; " + candidate + " is not pending");
        }
        if (openCanaries.findOpen(name, ReadConsistency.DIRECT).isPresent()) {
            throw new RolloutException(RolloutErrorCode.CANARY_ACTIVE, "policy " + name + " already has an open canary");
        }
        VersionBump bump = changeClassifier.classify(catalog.requireVersion(name, policy.stable()).definition(),
                catalog.requireVersion(name, candidate).definition());
        if (bump == VersionBump.MAJOR) {
            throw new RolloutException(RolloutErrorCode.CANARY_MAJOR_BLOCKED,
                    "MAJOR candidates are validated through shadow mode until explicit contracts exist");
        }
        Instant now = clock.instant();
        Canary canary = Canary.start(UUID.randomUUID().toString(), name, candidate, policy.stable(), command.weight(),
                command.duration(), command.criteria().orElse(globalCriteria), command.shadowRef(), command.actor().id(), now);
        canaries.save(canary);
        audit.canary("canary.started", canary, command.actor().id(), now, Map.of("bump", bump.name()));
        return CanaryView.of(canary);
    }

    @Override
    public CanaryView cancel(String policy, Actor actor, String reason) {
        return change(policy, actor, "canary.cancelled", reason, (canary, now) -> canary.cancel(reasonOr(reason, "cancelled by " + actor.id()), now));
    }

    @Override
    public CanaryView end(String policy, Actor actor, String reason) {
        return change(policy, actor, "canary.ended", reason, (canary, now) -> canary.end(reasonOr(reason, "ended by " + actor.id()), now));
    }

    @Override
    public CanaryView extend(String policy, Actor actor, Duration by) {
        return change(policy, actor, "canary.extended", "extended by " + by, (canary, now) -> canary.extend(by));
    }

    private CanaryView change(String policyName, Actor actor, String action, String reason, CanaryChange change) {
        PolicyName name = PolicyName.of(policyName);
        requireManageable(catalog.requirePolicy(name, ReadConsistency.DIRECT), actor);
        Canary canary = openCanaries.findOpen(name, ReadConsistency.DIRECT)
                .orElseThrow(() -> new RolloutException(RolloutErrorCode.CANARY_NOT_FOUND, "policy " + name + " has no open canary"));
        Instant now = clock.instant();
        change.apply(canary, now);
        canaries.save(canary);
        audit.canary(action, canary, actor.id(), now, Map.of("reason", reasonOr(reason, "")));
        return CanaryView.of(canary);
    }

    private static void requireManageable(Policy policy, Actor actor) {
        if (!policy.isManageableBy(actor)) {
            throw new CatalogException(CatalogErrorCode.NOT_OWNER,
                    actor.id() + " is neither owner of " + policy.name() + " nor operator");
        }
    }

    private static String reasonOr(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    @FunctionalInterface
    private interface CanaryChange {
        void apply(Canary canary, Instant now);
    }
}
