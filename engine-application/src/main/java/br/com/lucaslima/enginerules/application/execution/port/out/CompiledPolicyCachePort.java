package br.com.lucaslima.enginerules.application.execution.port.out;

import java.util.function.Supplier;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;

public interface CompiledPolicyCachePort {

    CompiledPolicy getOrCompile(PolicyName name, SemanticVersion version, Supplier<CompiledPolicy> compilation);
}
