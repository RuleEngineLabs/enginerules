package br.com.lucaslima.enginerules.domain.rollout.canary;

public record VersionStats(long samples, long errors, long p95Micros, long p99Micros) {

    public static final VersionStats EMPTY = new VersionStats(0, 0, 0, 0);

    public double errorRate() {
        return samples == 0 ? 0 : (double) errors / samples;
    }
}
