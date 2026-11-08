package br.com.lucaslima.enginerules.domain.execution;

import java.util.List;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.execution.spi.CompiledExpression;
import br.com.lucaslima.enginerules.domain.execution.spi.FieldReference;

public record GuardedExpression(CompiledExpression expression, List<FieldReference> requiredContextFields) {

    public GuardedExpression {
        requiredContextFields = List.copyOf(requiredContextFields);
    }

    public Object evaluate(Map<String, Object> context) {
        requiredContextFields.forEach(reference -> requirePresent(reference, context));
        return expression.evaluate(context);
    }

    private static void requirePresent(FieldReference reference, Map<String, Object> context) {
        if (!context.containsKey(reference.root())) {
            throw new MissingContextFieldException(reference.path());
        }
        if (reference.field() == null) {
            return;
        }
        if (!(context.get(reference.root()) instanceof Map<?, ?> value) || !value.containsKey(reference.field())) {
            throw new MissingContextFieldException(reference.path());
        }
    }
}
