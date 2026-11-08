package br.com.lucaslima.enginerules.domain.execution;

public record CompiledTransition(GuardedExpression when, String to) {
}
