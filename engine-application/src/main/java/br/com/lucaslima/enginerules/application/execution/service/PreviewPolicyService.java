package br.com.lucaslima.enginerules.application.execution.service;

import br.com.lucaslima.enginerules.application.execution.ExecutionLimits;
import br.com.lucaslima.enginerules.application.execution.port.in.PreviewPolicyUseCase;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOptions;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;
import br.com.lucaslima.enginerules.domain.execution.PolicyExecutor;

public final class PreviewPolicyService implements PreviewPolicyUseCase {

    private final PolicyCompiler compiler;
    private final PolicyExecutor executor;
    private final ExecutionLimits limits;

    public PreviewPolicyService(PolicyCompiler compiler, PolicyExecutor executor, ExecutionLimits limits) {
        this.compiler = compiler;
        this.executor = executor;
        this.limits = limits;
    }

    @Override
    public Result preview(Command command) {
        CompiledPolicy compiled = compiler.compile(command.definition());
        ExecutionOptions options = ExecutionOptions.preview(limits.timeout(), limits.stepBudget());
        return new Result(compiled.writeStates(), executor.execute(compiled, command.input(), options));
    }
}
