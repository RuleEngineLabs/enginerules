package br.com.lucaslima.enginerules.adapter.expression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionEvaluationException;
import br.com.lucaslima.enginerules.domain.execution.spi.FieldReference;

sealed interface Node {

    Object evaluate(Scope scope);

    void collectReferences(Set<FieldReference> references);

    record Scope(Map<String, Object> variables, Object current) {
        Scope withCurrent(Object element) {
            return new Scope(variables, element);
        }
    }

    record Literal(Object value) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            return value;
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
        }
    }

    record Variable(String name) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            return scope.variables().get(name);
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            references.add(FieldReference.rootOnly(name));
        }
    }

    record Current() implements Node {
        @Override
        public Object evaluate(Scope scope) {
            return scope.current();
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
        }
    }

    record Member(Node target, String field) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            return Member.read(target.evaluate(scope), field);
        }

        static Object read(Object container, String field) {
            if (container == null) {
                return null;
            }
            if (container instanceof Map<?, ?> map) {
                return map.get(field);
            }
            throw new ExpressionEvaluationException("cannot read field '" + field + "' of " + Values.describe(container));
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            if (target instanceof Variable variable) {
                references.add(new FieldReference(variable.name(), field));
            } else {
                target.collectReferences(references);
            }
        }
    }

    record Index(Node target, Node index) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            Object container = target.evaluate(scope);
            Object key = index.evaluate(scope);
            if (container == null || key == null) {
                return null;
            }
            if (container instanceof List<?> list && Values.isIntegral(key)) {
                long position = Values.asLong(key);
                long resolved = position < 0 ? list.size() + position : position;
                return resolved >= 0 && resolved < list.size() ? list.get((int) resolved) : null;
            }
            if (container instanceof Map<?, ?> && key instanceof String field) {
                return Member.read(container, field);
            }
            throw new ExpressionEvaluationException("cannot index " + Values.describe(container) + " with " + Values.describe(key));
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            if (target instanceof Variable variable && index instanceof Literal literal && literal.value() instanceof String field) {
                references.add(new FieldReference(variable.name(), field));
            } else {
                target.collectReferences(references);
            }
            index.collectReferences(references);
        }
    }

    record Not(Node operand) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            return !Values.truth(operand.evaluate(scope), "'!'");
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            operand.collectReferences(references);
        }
    }

    record Negate(Node operand) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            Object value = operand.evaluate(scope);
            if (Values.isIntegral(value)) {
                return Math.negateExact(Values.asLong(value));
            }
            if (Values.isNumber(value)) {
                return -Values.asDouble(value);
            }
            throw new ExpressionEvaluationException("cannot negate " + Values.describe(value));
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            operand.collectReferences(references);
        }
    }

    record Logical(String operator, Node left, Node right) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            boolean leftValue = Values.truth(left.evaluate(scope), "'" + operator + "'");
            if ("&&".equals(operator)) {
                return leftValue && Values.truth(right.evaluate(scope), "'&&'");
            }
            return leftValue || Values.truth(right.evaluate(scope), "'||'");
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            left.collectReferences(references);
            right.collectReferences(references);
        }
    }

    record Binary(String operator, Node left, Node right) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            Object leftValue = left.evaluate(scope);
            Object rightValue = right.evaluate(scope);
            return switch (operator) {
                case "==" -> Values.same(leftValue, rightValue);
                case "!=" -> !Values.same(leftValue, rightValue);
                case "<", "<=", ">", ">=" -> compare(leftValue, rightValue);
                case "in" -> Values.contains(rightValue, leftValue);
                case "not in" -> !Values.contains(rightValue, leftValue);
                default -> Values.arithmetic(operator, leftValue, rightValue);
            };
        }

        private boolean compare(Object leftValue, Object rightValue) {
            if (leftValue == null || rightValue == null) {
                return false;
            }
            int comparison = Values.compare(leftValue, rightValue, operator);
            return switch (operator) {
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                default -> comparison >= 0;
            };
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            left.collectReferences(references);
            right.collectReferences(references);
        }
    }

    record Conditional(Node condition, Node whenTrue, Node whenFalse) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            return Values.truth(condition.evaluate(scope), "'?:'") ? whenTrue.evaluate(scope) : whenFalse.evaluate(scope);
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            condition.collectReferences(references);
            whenTrue.collectReferences(references);
            whenFalse.collectReferences(references);
        }
    }

    record ListLiteral(List<Node> items) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            List<Object> values = new ArrayList<>(items.size());
            items.forEach(item -> values.add(item.evaluate(scope)));
            return values;
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            items.forEach(item -> item.collectReferences(references));
        }
    }

    record MapLiteral(Map<String, Node> entries) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            Map<String, Object> values = new LinkedHashMap<>();
            entries.forEach((key, value) -> values.put(key, value.evaluate(scope)));
            return values;
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            entries.values().forEach(value -> value.collectReferences(references));
        }
    }

    record Call(Builtin function, List<Node> arguments) implements Node {
        @Override
        public Object evaluate(Scope scope) {
            return function.invoke(arguments, scope);
        }

        @Override
        public void collectReferences(Set<FieldReference> references) {
            arguments.forEach(argument -> argument.collectReferences(references));
        }
    }
}
