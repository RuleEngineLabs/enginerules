package br.com.lucaslima.enginerules.adapter.expression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionEvaluationException;

enum Builtin {
    LEN("len", 1, 1, false),
    CONTAINS("contains", 2, 2, false),
    STARTS_WITH("startsWith", 2, 2, false),
    ENDS_WITH("endsWith", 2, 2, false),
    LOWER("lower", 1, 1, false),
    UPPER("upper", 1, 1, false),
    TRIM("trim", 1, 1, false),
    HAS("has", 2, 2, false),
    ABS("abs", 1, 1, false),
    MIN("min", 1, Integer.MAX_VALUE, false),
    MAX("max", 1, Integer.MAX_VALUE, false),
    SUM("sum", 1, 1, false),
    STRING("string", 1, 1, false),
    INT("int", 1, 1, false),
    FLOAT("float", 1, 1, false),
    ALL("all", 2, 2, true),
    ANY("any", 2, 2, true),
    NONE("none", 2, 2, true),
    FILTER("filter", 2, 2, true),
    MAP("map", 2, 2, true),
    COUNT("count", 2, 2, true);

    private final String contractName;
    private final int minArguments;
    private final int maxArguments;
    private final boolean iterates;

    Builtin(String contractName, int minArguments, int maxArguments, boolean iterates) {
        this.contractName = contractName;
        this.minArguments = minArguments;
        this.maxArguments = maxArguments;
        this.iterates = iterates;
    }

    static Optional<Builtin> named(String name) {
        return Arrays.stream(values()).filter(builtin -> builtin.contractName.equals(name)).findFirst();
    }

    String contractName() {
        return contractName;
    }

    boolean accepts(int argumentCount) {
        return argumentCount >= minArguments && argumentCount <= maxArguments;
    }

    Object invoke(List<Node> arguments, Node.Scope scope) {
        if (iterates) {
            return iterate(items(arguments.get(0).evaluate(scope)), arguments.get(1), scope);
        }
        List<Object> values = new ArrayList<>(arguments.size());
        arguments.forEach(argument -> values.add(argument.evaluate(scope)));
        return apply(values);
    }

    private Object iterate(List<?> items, Node body, Node.Scope scope) {
        return switch (this) {
            case ALL -> items.stream().allMatch(item -> test(body, scope, item));
            case ANY -> items.stream().anyMatch(item -> test(body, scope, item));
            case NONE -> items.stream().noneMatch(item -> test(body, scope, item));
            case FILTER -> items.stream().filter(item -> test(body, scope, item)).map(item -> (Object) item).toList();
            case MAP -> {
                List<Object> mapped = new ArrayList<>(items.size());
                items.forEach(item -> mapped.add(body.evaluate(scope.withCurrent(item))));
                yield mapped;
            }
            case COUNT -> items.stream().filter(item -> test(body, scope, item)).count();
            default -> throw new IllegalStateException(contractName);
        };
    }

    private boolean test(Node body, Node.Scope scope, Object item) {
        return Values.truth(body.evaluate(scope.withCurrent(item)), contractName + " predicate");
    }

    private Object apply(List<Object> values) {
        Object first = values.get(0);
        return switch (this) {
            case LEN -> length(first);
            case CONTAINS -> Values.contains(first, values.get(1));
            case STARTS_WITH -> first != null && text(first).startsWith(text(values.get(1)));
            case ENDS_WITH -> first != null && text(first).endsWith(text(values.get(1)));
            case LOWER -> first == null ? null : text(first).toLowerCase();
            case UPPER -> first == null ? null : text(first).toUpperCase();
            case TRIM -> first == null ? null : text(first).trim();
            case HAS -> first instanceof Map<?, ?> map && map.containsKey(values.get(1));
            case ABS -> absolute(first);
            case MIN -> extreme(values, -1);
            case MAX -> extreme(values, 1);
            case SUM -> sum(items(first));
            case STRING -> first == null ? null : String.valueOf(first);
            case INT -> toInteger(first);
            case FLOAT -> toDecimal(first);
            default -> throw new IllegalStateException(contractName);
        };
    }

    private long length(Object value) {
        return switch (value) {
            case null -> 0L;
            case String text -> (long) text.length();
            case Collection<?> items -> (long) items.size();
            case Map<?, ?> map -> (long) map.size();
            default -> throw new ExpressionEvaluationException("len expects a string, list or map but got " + Values.describe(value));
        };
    }

    private Object absolute(Object value) {
        if (Values.isIntegral(value)) {
            return Math.absExact(Values.asLong(value));
        }
        if (Values.isNumber(value)) {
            return Math.abs(Values.asDouble(value));
        }
        throw new ExpressionEvaluationException("abs expects a number but got " + Values.describe(value));
    }

    private Object extreme(List<Object> values, int direction) {
        List<?> candidates = values.size() == 1 && values.get(0) instanceof List<?> list ? list : values;
        Object best = null;
        for (Object candidate : candidates) {
            if (!Values.isNumber(candidate)) {
                throw new ExpressionEvaluationException(contractName + " expects numbers but got " + Values.describe(candidate));
            }
            if (best == null || Integer.signum(Values.compareNumbers(candidate, best)) == direction) {
                best = candidate;
            }
        }
        return best;
    }

    private Object sum(List<?> items) {
        Object total = 0L;
        for (Object item : items) {
            total = Values.arithmetic("+", total, item);
        }
        return total;
    }

    private Object toInteger(Object value) {
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException invalid) {
                throw new ExpressionEvaluationException("int cannot parse '" + text + "'");
            }
        }
        if (Values.isNumber(value)) {
            return (long) Values.asDouble(value);
        }
        throw new ExpressionEvaluationException("int expects a number or string but got " + Values.describe(value));
    }

    private Object toDecimal(Object value) {
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException invalid) {
                throw new ExpressionEvaluationException("float cannot parse '" + text + "'");
            }
        }
        if (Values.isNumber(value)) {
            return Values.asDouble(value);
        }
        throw new ExpressionEvaluationException("float expects a number or string but got " + Values.describe(value));
    }

    private String text(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new ExpressionEvaluationException(contractName + " expects a string but got " + Values.describe(value));
    }

    private List<?> items(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list;
        }
        throw new ExpressionEvaluationException(contractName + " expects a list but got " + Values.describe(value));
    }
}
