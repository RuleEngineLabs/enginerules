package br.com.lucaslima.enginerules.adapter.persistence.filesystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import br.com.lucaslima.enginerules.adapter.json.Json;
import br.com.lucaslima.enginerules.adapter.persistence.documents.PolicyDocuments;
import br.com.lucaslima.enginerules.application.rollout.port.out.ExecutionSample;
import br.com.lucaslima.enginerules.application.rollout.port.out.QueryMetricsPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.RecordMetricsPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowSample;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.canary.VersionStats;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;

public final class FileSystemMetrics implements RecordMetricsPort, QueryMetricsPort {

    private final Path executionsRoot;
    private final Path shadowsRoot;
    private final int maxBufferedSamples;
    private final Queue<Pending> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger buffered = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();

    public FileSystemMetrics(Path root, int maxBufferedSamples) {
        this.executionsRoot = root.resolve("metrics").resolve("executions");
        this.shadowsRoot = root.resolve("metrics").resolve("shadows");
        this.maxBufferedSamples = maxBufferedSamples;
    }

    @Override
    public void recordExecution(ExecutionSample sample) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", sample.at().toString());
        line.put("version", sample.version().toString());
        line.put("route", sample.route().label());
        line.put("error", sample.error());
        line.put("durationMicros", sample.durationMicros());
        enqueue(executionsRoot.resolve(sample.policy().value() + ".jsonl"), line);
    }

    @Override
    public void recordShadow(ShadowSample sample) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", sample.at().toString());
        line.put("result", sample.result().name());
        line.put("latencyRatio", sample.latencyRatio());
        enqueue(shadowsRoot.resolve(sample.policy().value()).resolve(sample.shadowId() + ".jsonl"), line);
    }

    public synchronized void flush() {
        Map<Path, StringBuilder> byFile = new LinkedHashMap<>();
        Pending pending;
        while ((pending = buffer.poll()) != null) {
            buffered.decrementAndGet();
            byFile.computeIfAbsent(pending.file(), ignored -> new StringBuilder())
                    .append(Json.write(pending.line())).append(System.lineSeparator());
        }
        byFile.forEach(FileSystemMetrics::append);
    }

    public int discardedSamples() {
        return discarded.get();
    }

    @Override
    public VersionStats versionStats(PolicyName policy, SemanticVersion version, Route route, Instant from, Instant to) {
        flush();
        List<Long> durations = new ArrayList<>();
        long[] errors = {0};
        lines(executionsRoot.resolve(policy.value() + ".jsonl"))
                .filter(line -> version.toString().equals(line.get("version")) && route.label().equals(line.get("route")))
                .filter(line -> within(Instant.parse((String) line.get("at")), from, to))
                .forEach(line -> {
                    durations.add(((Number) line.get("durationMicros")).longValue());
                    if (Boolean.TRUE.equals(line.get("error"))) {
                        errors[0]++;
                    }
                });
        durations.sort(Long::compare);
        return new VersionStats(durations.size(), errors[0], percentile(durations, 0.95), percentile(durations, 0.99));
    }

    @Override
    public ShadowStats shadowStats(PolicyName policy, String shadowId) {
        flush();
        Map<ComparisonResult, Long> counts = new EnumMap<>(ComparisonResult.class);
        List<Double> ratios = new ArrayList<>();
        lines(shadowsRoot.resolve(policy.value()).resolve(shadowId + ".jsonl")).forEach(line -> {
            ComparisonResult result = ComparisonResult.valueOf((String) line.get("result"));
            counts.merge(result, 1L, Long::sum);
            if (result != ComparisonResult.DROPPED && result != ComparisonResult.NOT_COMPARABLE) {
                ratios.add(((Number) line.get("latencyRatio")).doubleValue());
            }
        });
        ratios.sort(Double::compare);
        return new ShadowStats(counts, percentileOf(ratios, 0.5), percentileOf(ratios, 0.95));
    }

    private void enqueue(Path file, Map<String, Object> line) {
        if (buffered.incrementAndGet() > maxBufferedSamples) {
            buffered.decrementAndGet();
            discarded.incrementAndGet();
            return;
        }
        buffer.add(new Pending(file, line));
    }

    private static boolean within(Instant at, Instant from, Instant to) {
        return !at.isBefore(from) && !at.isAfter(to);
    }

    private static long percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1)));
    }

    private static double percentileOf(List<Double> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1)));
    }

    private static Stream<Map<String, Object>> lines(Path file) {
        if (!Files.isRegularFile(file)) {
            return Stream.empty();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> PolicyDocuments.object(Json.parse(line)));
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + file, failure);
        }
    }

    private static void append(Path file, StringBuilder content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot append metrics to " + file, failure);
        }
    }

    private record Pending(Path file, Map<String, Object> line) {
    }
}
