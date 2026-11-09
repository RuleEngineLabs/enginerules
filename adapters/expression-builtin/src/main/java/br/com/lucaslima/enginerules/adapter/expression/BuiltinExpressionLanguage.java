package br.com.lucaslima.enginerules.adapter.expression;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import br.com.lucaslima.enginerules.domain.execution.spi.CompiledExpression;
import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionEvaluationException;
import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionLanguage;
import br.com.lucaslima.enginerules.domain.execution.spi.FieldReference;

public final class BuiltinExpressionLanguage implements ExpressionLanguage {

    @Override
    public CompiledExpression compile(String source) {
        Node root = Parser.parse(source);
        Set<FieldReference> references = new LinkedHashSet<>();
        root.collectReferences(references);
        return new TreeExpression(source, root, Set.copyOf(references));
    }

    private record TreeExpression(String source, Node root, Set<FieldReference> references) implements CompiledExpression {

        @Override
        public Object evaluate(Map<String, Object> variables) {
            try {
                return root.evaluate(new Node.Scope(variables, null));
            } catch (ArithmeticException overflow) {
                throw new ExpressionEvaluationException("'" + source + "': " + overflow.getMessage());
            } catch (ExpressionEvaluationException failure) {
                throw new ExpressionEvaluationException("'" + source + "': " + failure.getMessage());
            }
        }
    }
}
