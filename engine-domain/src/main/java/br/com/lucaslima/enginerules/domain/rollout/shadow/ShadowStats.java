package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.util.EnumMap;
import java.util.Map;

public record ShadowStats(Map<ComparisonResult, Long> counts, double latencyRatioP50, double latencyRatioP95) {

    public ShadowStats {
        EnumMap<ComparisonResult, Long> complete = new EnumMap<>(ComparisonResult.class);
        for (ComparisonResult result : ComparisonResult.values()) {
            complete.put(result, counts.getOrDefault(result, 0L));
        }
        counts = Map.copyOf(complete);
    }

    public long count(ComparisonResult result) {
        return counts.get(result);
    }

    public long comparable() {
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey() != ComparisonResult.NOT_COMPARABLE && entry.getKey() != ComparisonResult.DROPPED)
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    public double equalityRate() {
        return ratio(count(ComparisonResult.EQUAL), comparable());
    }

    public double inputIncompatibilityRate() {
        return ratio(count(ComparisonResult.SHADOW_ERROR_ONLY), comparable());
    }

    public double notComparableRate() {
        long executed = comparable() + count(ComparisonResult.NOT_COMPARABLE);
        return ratio(count(ComparisonResult.NOT_COMPARABLE), executed);
    }

    private static double ratio(long part, long total) {
        return total == 0 ? 0 : (double) part / total;
    }
}
