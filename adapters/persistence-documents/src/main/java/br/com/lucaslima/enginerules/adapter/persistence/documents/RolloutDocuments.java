package br.com.lucaslima.enginerules.adapter.persistence.documents;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanarySnapshot;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryStatus;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowSnapshot;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualRoute;

public final class RolloutDocuments {

    private RolloutDocuments() {
    }

    public static Map<String, Object> fromCanary(CanarySnapshot canary, long revision) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("id", canary.id());
        tree.put("policy", canary.policy().value());
        tree.put("candidate", canary.candidate().toString());
        tree.put("baseline", canary.baseline().toString());
        tree.put("weight", canary.weight());
        tree.put("duration", canary.duration().toString());
        tree.put("extension", canary.extension().toString());
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("errorMarginPercentagePoints", canary.criteria().errorMarginPercentagePoints());
        criteria.put("maxLatencyRatio", canary.criteria().maxLatencyRatio());
        criteria.put("minSamples", canary.criteria().minSamples());
        criteria.put("maxInputIncompatibilityRate", canary.criteria().maxInputIncompatibilityRate());
        tree.put("criteria", criteria);
        tree.put("shadowRef", canary.shadowRef());
        tree.put("status", canary.status().name());
        tree.put("awaitingExtension", canary.awaitingExtension());
        tree.put("startedBy", canary.startedBy());
        tree.put("startedAt", canary.startedAt().toString());
        tree.put("closedAt", text(canary.closedAt()));
        tree.put("closingReason", canary.closingReason());
        tree.put("revision", revision);
        return tree;
    }

    public static CanarySnapshot toCanary(Map<String, Object> tree) {
        Map<String, Object> criteria = PolicyDocuments.object(tree.get("criteria"));
        return new CanarySnapshot(
                (String) tree.get("id"),
                PolicyName.of((String) tree.get("policy")),
                SemanticVersion.parse((String) tree.get("candidate")),
                SemanticVersion.parse((String) tree.get("baseline")),
                number(tree.get("weight")).intValue(),
                Duration.parse((String) tree.get("duration")),
                Duration.parse((String) tree.get("extension")),
                new CanaryCriteria(
                        number(criteria.get("errorMarginPercentagePoints")).doubleValue(),
                        number(criteria.get("maxLatencyRatio")).doubleValue(),
                        number(criteria.get("minSamples")).longValue(),
                        number(criteria.get("maxInputIncompatibilityRate")).doubleValue()),
                (String) tree.get("shadowRef"),
                CanaryStatus.valueOf((String) tree.get("status")),
                Boolean.TRUE.equals(tree.get("awaitingExtension")),
                (String) tree.get("startedBy"),
                Instant.parse((String) tree.get("startedAt")),
                instant(tree.get("closedAt")),
                (String) tree.get("closingReason"),
                number(tree.get("revision")).longValue());
    }

    public static Map<String, Object> fromShadow(ShadowSnapshot shadow, long revision) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("id", shadow.id());
        tree.put("policy", shadow.policy().value());
        tree.put("target", shadow.target().toString());
        tree.put("percentage", shadow.percentage());
        List<Object> routes = new ArrayList<>();
        shadow.virtualRoutes().forEach(route -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("connection", route.connection());
            item.put("method", route.method());
            item.put("pathTemplate", route.pathTemplate());
            item.put("status", route.status());
            item.put("body", route.body());
            routes.add(item);
        });
        tree.put("virtualRoutes", routes);
        tree.put("ignoredPaths", shadow.ignoredPaths());
        tree.put("status", shadow.status().name());
        tree.put("startedBy", shadow.startedBy());
        tree.put("startedAt", shadow.startedAt().toString());
        tree.put("expiresAt", shadow.expiresAt().toString());
        tree.put("endedAt", text(shadow.endedAt()));
        tree.put("revision", revision);
        return tree;
    }

    public static ShadowSnapshot toShadow(Map<String, Object> tree) {
        List<VirtualRoute> routes = new ArrayList<>();
        for (Object raw : list(tree.get("virtualRoutes"))) {
            Map<String, Object> item = PolicyDocuments.object(raw);
            routes.add(new VirtualRoute((String) item.get("connection"), (String) item.get("method"),
                    (String) item.get("pathTemplate"), number(item.get("status")).intValue(), item.get("body")));
        }
        List<String> ignored = list(tree.get("ignoredPaths")).stream().map(String::valueOf).toList();
        return new ShadowSnapshot(
                (String) tree.get("id"),
                PolicyName.of((String) tree.get("policy")),
                SemanticVersion.parse((String) tree.get("target")),
                number(tree.get("percentage")).intValue(),
                routes,
                ignored,
                ShadowStatus.valueOf((String) tree.get("status")),
                (String) tree.get("startedBy"),
                Instant.parse((String) tree.get("startedAt")),
                Instant.parse((String) tree.get("expiresAt")),
                instant(tree.get("endedAt")),
                number(tree.get("revision")).longValue());
    }

    private static Number number(Object value) {
        return (Number) value;
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse((String) value);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }
}
