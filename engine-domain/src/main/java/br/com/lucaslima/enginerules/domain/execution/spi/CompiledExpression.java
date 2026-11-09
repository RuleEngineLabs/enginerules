package br.com.lucaslima.enginerules.domain.execution.spi;

import java.util.Map;
import java.util.Set;

public interface CompiledExpression {

    Object evaluate(Map<String, Object> variables);

    Set<FieldReference> references();

    String source();
}
