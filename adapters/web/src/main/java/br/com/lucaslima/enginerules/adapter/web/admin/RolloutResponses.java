package br.com.lucaslima.enginerules.adapter.web.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryView;
import br.com.lucaslima.enginerules.application.rollout.port.in.EvaluateCanariesUseCase;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowReport;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowView;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanarySnapshot;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowSnapshot;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;

final class RolloutResponses {

    private RolloutResponses() {
    }

    static Map<String, Object> canary(CanaryView view) {
        CanarySnapshot canary = view.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", canary.id());
        body.put("policy", canary.policy().value());
        body.put("candidate", canary.candidate().toString());
        body.put("baseline", canary.baseline().toString());
        body.put("weight", canary.weight());
        body.put("status", canary.status().name());
        body.put("awaitingExtension", canary.awaitingExtension());
        body.put("startedBy", canary.startedBy());
        body.put("startedAt", canary.startedAt().toString());
        body.put("duration", canary.duration().toString());
        body.put("extension", canary.extension().toString());
        body.put("decisionDeadline", view.decisionDeadline());
        body.put("absoluteDeadline", view.absoluteDeadline());
        body.put("shadowRef", canary.shadowRef());
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("errorMarginPercentagePoints", canary.criteria().errorMarginPercentagePoints());
        criteria.put("maxLatencyRatio", canary.criteria().maxLatencyRatio());
        criteria.put("minSamples", canary.criteria().minSamples());
        criteria.put("maxInputIncompatibilityRate", canary.criteria().maxInputIncompatibilityRate());
        body.put("criteria", criteria);
        body.put("closedAt", canary.closedAt() == null ? null : canary.closedAt().toString());
        body.put("closingReason", canary.closingReason());
        return body;
    }

    static Map<String, Object> shadow(ShadowView view) {
        ShadowSnapshot shadow = view.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", shadow.id());
        body.put("policy", shadow.policy().value());
        body.put("target", shadow.target().toString());
        body.put("percentage", shadow.percentage());
        body.put("status", view.effectiveStatus().name());
        body.put("startedBy", shadow.startedBy());
        body.put("startedAt", shadow.startedAt().toString());
        body.put("expiresAt", shadow.expiresAt().toString());
        body.put("ignoredPaths", shadow.ignoredPaths());
        body.put("virtualRoutes", shadow.virtualRoutes().size());
        return body;
    }

    static Map<String, Object> report(ShadowReport report) {
        ShadowStats stats = report.stats();
        Map<String, Object> counts = new LinkedHashMap<>();
        for (ComparisonResult result : ComparisonResult.values()) {
            counts.put(result.name(), stats.count(result));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shadow", shadow(report.shadow()));
        body.put("counts", counts);
        body.put("equalityRate", stats.equalityRate());
        body.put("inputIncompatibilityRate", stats.inputIncompatibilityRate());
        body.put("notComparableRate", stats.notComparableRate());
        body.put("latencyRatioP50", stats.latencyRatioP50());
        body.put("latencyRatioP95", stats.latencyRatioP95());
        return body;
    }

    static Map<String, Object> evaluation(EvaluateCanariesUseCase.Evaluation evaluation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policy", evaluation.policy());
        body.put("canaryId", evaluation.canaryId());
        body.put("verdict", evaluation.verdict());
        body.put("reasons", evaluation.reasons());
        return body;
    }
}
