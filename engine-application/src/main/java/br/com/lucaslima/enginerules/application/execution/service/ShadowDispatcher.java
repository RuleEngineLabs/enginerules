package br.com.lucaslima.enginerules.application.execution.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.execution.ExecutionLimits;
import br.com.lucaslima.enginerules.application.execution.port.out.CompiledPolicyCachePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.LoadCurrentShadowPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.RecordMetricsPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowQueuePort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowSample;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOptions;
import br.com.lucaslima.enginerules.domain.execution.ExecutionReport;
import br.com.lucaslima.enginerules.domain.execution.IoBinding;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.execution.PolicyExecutor;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ComparisonResult;
import br.com.lucaslima.enginerules.domain.rollout.shadow.IoRecording;
import br.com.lucaslima.enginerules.domain.rollout.shadow.RecordedIo;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowComparator;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualIo;

public final class ShadowDispatcher {

    private final LoadCurrentShadowPort shadows;
    private final ShadowQueuePort queue;
    private final RecordMetricsPort metrics;
    private final CatalogLookup catalog;
    private final CompiledPolicyCachePort compiledPolicies;
    private final PolicyCompiler compiler;
    private final PolicyExecutor executor;
    private final ShadowComparator comparator;
    private final ExecutionLimits limits;
    private final RandomGenerator random;
    private final Clock clock;

    public ShadowDispatcher(LoadCurrentShadowPort shadows, ShadowQueuePort queue, RecordMetricsPort metrics,
                            CatalogLookup catalog, CompiledPolicyCachePort compiledPolicies, PolicyCompiler compiler,
                            PolicyExecutor executor, ShadowComparator comparator, ExecutionLimits limits,
                            RandomGenerator random, Clock clock) {
        this.shadows = shadows;
        this.queue = queue;
        this.metrics = metrics;
        this.catalog = catalog;
        this.compiledPolicies = compiledPolicies;
        this.compiler = compiler;
        this.executor = executor;
        this.comparator = comparator;
        this.limits = limits;
        this.random = random;
        this.clock = clock;
    }

    public Optional<Copy> select(PolicyName policy, SemanticVersion executedVersion, Optional<String> consumerId) {
        Instant now = clock.instant();
        return shadows.findCurrent(policy, ReadConsistency.CACHED)
                .filter(shadow -> !shadow.target().equals(executedVersion))
                .filter(shadow -> shadow.copies(consumerId, now, random))
                .map(shadow -> new Copy(shadow, new IoRecording(executor.defaultIo())));
    }

    public void dispatch(Copy copy, Map<String, Object> input, ExecutionReport realReport) {
        RecordedIo recorded = copy.recording().recorded();
        boolean accepted = queue.offer(() -> runShadow(copy.shadow(), input, realReport, recorded));
        if (!accepted) {
            record(copy.shadow(), ComparisonResult.DROPPED, 0);
        }
    }

    private void runShadow(Shadow shadow, Map<String, Object> input, ExecutionReport realReport, RecordedIo recorded) {
        try {
            CompiledPolicy target = compiledPolicies.getOrCompile(shadow.policy(), shadow.target(),
                    () -> compiler.compile(catalog.requireVersion(shadow.policy(), shadow.target()).definition()));
            VirtualIo virtualIo = new VirtualIo(recorded, shadow.virtualRoutes());
            ExecutionReport shadowReport = executor.execute(target, input,
                    ExecutionOptions.live(false, limits.timeout(), limits.stepBudget()), virtualIo.binding());
            ComparisonResult result = comparator.compare(realReport.outcome(), shadowReport.outcome(), virtualIo.missed(),
                    shadow.ignoredPaths());
            record(shadow, result, latencyRatio(realReport, shadowReport));
        } catch (RuntimeException failure) {
            record(shadow, ComparisonResult.NOT_COMPARABLE, 0);
        }
    }

    private void record(Shadow shadow, ComparisonResult result, double latencyRatio) {
        metrics.recordShadow(new ShadowSample(clock.instant(), shadow.policy(), shadow.id(), result, latencyRatio));
    }

    private static double latencyRatio(ExecutionReport real, ExecutionReport shadow) {
        long realNanos = Math.max(real.elapsed().toNanos(), 1);
        return (double) shadow.elapsed().toNanos() / realNanos;
    }

    public record Copy(Shadow shadow, IoRecording recording) {
        public IoBinding binding() {
            return recording.binding();
        }
    }
}
