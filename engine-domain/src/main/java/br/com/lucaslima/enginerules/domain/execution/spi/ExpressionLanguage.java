package br.com.lucaslima.enginerules.domain.execution.spi;

public interface ExpressionLanguage {

    CompiledExpression compile(String source);
}
