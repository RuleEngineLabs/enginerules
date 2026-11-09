package br.com.lucaslima.enginerules.application.execution.service;

import java.time.Clock;
import java.util.Optional;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.service.CatalogLookup;
import br.com.lucaslima.enginerules.application.execution.ExecutionLimits;
import br.com.lucaslima.enginerules.application.execution.port.in.ExecutePolicyUseCase;
import br.com.lucaslima.enginerules.application.execution.port.out.CompiledPolicyCachePort;
import br.com.lucaslima.enginerules.application.execution.port.out.ExecutionTogglesPort;
import br.com.lucaslima.enginerules.application.rollout.port.out.ExecutionSample;
import br.com.lucaslima.enginerules.application.rollout.port.out.RecordMetricsPort;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionSelector;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOptions;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;
import br.com.lucaslima.enginerules.domain.execution.ExecutionReport;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.execution.PolicyExecutor;

public final class ExecutePolicyService implements ExecutePolicyUseCase {

    private final CatalogLookup catalog;
    private final CompiledPolicyCachePort compiledPolicies;
    private final ExecutionTogglesPort toggles;
    private final PolicyCompiler compiler;
    private final PolicyExecutor executor;
    private final ExecutionLimits limits;
    private final TrafficRouter router;
    private final ShadowDispatcher shadows;
    private final RecordMetricsPort metrics;
    private final Clock clock;

    public ExecutePolicyService(CatalogLookup catalog, CompiledPolicyCachePort compiledPolicies, ExecutionTogglesPort toggles,
                                PolicyCompiler compiler, PolicyExecutor executor, ExecutionLimits limits,
                                TrafficRouter router, ShadowDispatcher shadows, RecordMetricsPort metrics, Clock clock) {
        this.catalog = catalog;
        this.compiledPolicies = compiledPolicies;
        this.toggles = toggles;
        this.compiler = compiler;
        this.executor = executor;
        this.limits = limits;
        this.router = router;
        this.shadows = shadows;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Result execute(Command command) {
        PolicyName name = PolicyName.of(command.name());
        ReadConsistency consistency = command.noCache() ? ReadConsistency.DIRECT : ReadConsistency.CACHED;
        Policy policy = catalog.requirePolicy(name, consistency);
        TrafficRouter.Resolution resolution = router.resolve(policy, VersionSelector.parse(command.requestedVersion()),
                command.consumerId(), consistency);
        SemanticVersion version = resolution.version();
        CompiledPolicy compiled = command.noCache()
                ? compile(name, version)
                : compiledPolicies.getOrCompile(name, version, () -> compile(name, version));
        ExecutionOptions options = ExecutionOptions.live(toggles.traceEnabledFor(name), limits.timeout(), limits.stepBudget());
        Optional<ShadowDispatcher.Copy> copy = resolution.route().servesRealTraffic() && !command.noCache()
                ? shadows.select(name, version, command.consumerId())
                : Optional.empty();
        ExecutionReport report = copy
                .map(selected -> executor.execute(compiled, command.input(), options, selected.binding()))
                .orElseGet(() -> executor.execute(compiled, command.input(), options));
        copy.ifPresent(selected -> shadows.dispatch(selected, command.input(), report));
        metrics.recordExecution(new ExecutionSample(clock.instant(), name, version, resolution.route(), isError(report.outcome()),
                report.elapsed().toNanos() / 1_000));
        return new Result(name.value(), version.toString(), resolution.route().label(), report);
    }

    private CompiledPolicy compile(PolicyName name, SemanticVersion version) {
        return compiler.compile(catalog.requireVersion(name, version).definition());
    }

    private static boolean isError(ExecutionOutcome outcome) {
        return switch (outcome) {
            case ExecutionOutcome.Failed ignored -> true;
            case ExecutionOutcome.Responded responded -> responded.status() >= 500;
            case ExecutionOutcome.PreviewBlocked ignored -> false;
        };
    }
}
