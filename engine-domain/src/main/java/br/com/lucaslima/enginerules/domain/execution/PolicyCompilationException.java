package br.com.lucaslima.enginerules.domain.execution;

import java.util.List;
import java.util.stream.Collectors;

public final class PolicyCompilationException extends RuntimeException {

    private final List<CompilationViolation> violations;

    public PolicyCompilationException(List<CompilationViolation> violations) {
        super("policy does not compile: " + violations.stream().map(Object::toString).collect(Collectors.joining("; ")));
        this.violations = List.copyOf(violations);
    }

    public List<CompilationViolation> violations() {
        return violations;
    }
}
