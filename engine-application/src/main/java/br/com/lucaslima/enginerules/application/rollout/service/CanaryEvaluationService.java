package br.com.lucaslima.enginerules.application.rollout.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.rollout.port.in.EvaluateCanariesUseCase;
import br.com.lucaslima.enginerules.application.rollout.port.out.CanaryStorePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.QueryMetricsPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowStorePort;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryEvaluator;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryStatus;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryVerdict;
import br.com.lucaslima.enginerules.domain.rollout.canary.VersionStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;

public final class CanaryEvaluationService implements EvaluateCanariesUseCase {

    public static final Actor EVALUATOR = new Actor("canary-evaluator", Set.of(Actor.OPERATORS_GROUP));

    private final CanaryStorePort canaries;
    private final ShadowStorePort shadows;
    private final QueryMetricsPort metrics;
    private final CatalogLookup catalog;
    private final SavePolicyPort savePolicy;
    private final AppendAuditPort auditPort;
    private final RolloutAudit audit;
    private final CanaryEvaluator evaluator;
    private final Clock clock;

    public CanaryEvaluationService(CanaryStorePort canaries, ShadowStorePort shadows, QueryMetricsPort metrics,
                                   CatalogLookup catalog, SavePolicyPort savePolicy, AppendAuditPort audit,
                                   CanaryEvaluator evaluator, Clock clock) {
        this.canaries = canaries;
        this.shadows = shadows;
        this.metrics = metrics;
        this.catalog = catalog;
        this.savePolicy = savePolicy;
        this.auditPort = audit;
        this.audit = new RolloutAudit(audit);
        this.evaluator = evaluator;
        this.clock = clock;
    }

    @Override
    public List<Evaluation> evaluateOpenCanaries() {
        List<Evaluation> evaluations = new ArrayList<>();
        for (Canary canary : canaries.listOpen()) {
            evaluations.add(evaluate(canary));
        }
        return evaluations;
    }

    private Evaluation evaluate(Canary canary) {
        Instant now = clock.instant();
        if (canary.status() == CanaryStatus.CONCLUDING) {
            promote(canary, now);
            return evaluation(canary, "promoted", List.of("resumed an interrupted promotion"));
        }
        VersionStats baseline = metrics.versionStats(canary.policy(), canary.baseline(), Route.STABLE, canary.startedAt(), now);
        VersionStats candidate = metrics.versionStats(canary.policy(), canary.candidate(), Route.CANARY, canary.startedAt(), now);
        CanaryVerdict verdict = evaluator.evaluate(canary, baseline, candidate, shadowStats(canary, now), now);
        return switch (verdict) {
            case CanaryVerdict.KeepObserving ignored -> evaluation(canary, "observing", List.of());
            case CanaryVerdict.Inconclusive inconclusive -> {
                if (!canary.awaitingExtension()) {
                    canary.markInconclusive();
                    canaries.save(canary);
                    audit.canary("canary.inconclusive", canary, EVALUATOR.id(), now, Map.of("reason", inconclusive.reason()));
                }
                yield evaluation(canary, "inconclusive", List.of(inconclusive.reason()));
            }
            case CanaryVerdict.Abort abort -> {
                canary.cancel(String.join("; ", abort.reasons()), now);
                canaries.save(canary);
                audit.canary("canary.aborted", canary, EVALUATOR.id(), now, Map.of("reasons", String.join("; ", abort.reasons()),
                        "candidateSamples", String.valueOf(candidate.samples()), "baselineSamples", String.valueOf(baseline.samples())));
                yield evaluation(canary, "aborted", abort.reasons());
            }
            case CanaryVerdict.Promote ignored -> {
                canary.beginConclusion();
                canaries.save(canary);
                promote(canary, now);
                yield evaluation(canary, "promoted", List.of());
            }
        };
    }

    private void promote(Canary canary, Instant now) {
        Policy policy = catalog.requirePolicy(canary.policy(), ReadConsistency.DIRECT);
        if (!policy.stable().equals(canary.candidate())) {
            policy.moveStable(EVALUATOR, canary.candidate(), now);
            savePolicy.savePolicy(policy, Optional.empty());
            auditPort.append(policy.drainEvents().stream().map(AuditRecord::from).toList());
        }
        canary.completePromotion(now);
        canaries.save(canary);
        audit.canary("canary.promoted", canary, EVALUATOR.id(), now, Map.of());
    }

    private Optional<ShadowStats> shadowStats(Canary canary, Instant now) {
        return canary.shadowRef()
                .flatMap(id -> shadows.findShadow(canary.policy(), id))
                .filter(shadow -> shadow.isLiveAt(now))
                .map(shadow -> metrics.shadowStats(canary.policy(), shadow.id()));
    }

    private static Evaluation evaluation(Canary canary, String verdict, List<String> reasons) {
        return new Evaluation(canary.policy().value(), canary.id(), verdict, reasons);
    }
}
