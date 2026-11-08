package br.com.lucaslima.enginerules.adapter.expression;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionEvaluationException;

final class Values {

    private Values() {
    }

    static boolean isNumber(Object value) {
        return value instanceof Number;
    }

    static boolean isIntegral(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                || (value instanceof BigInteger big && big.bitLength() < 64);
    }

    static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    static double asDouble(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return ((Number) value).doubleValue();
    }

    static boolean truth(Object value, String operation) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new ExpressionEvaluationException(operation + " expects a boolean but got " + describe(value));
    }

    static boolean same(Object left, Object right) {
        if (isNumber(left) && isNumber(right)) {
            return compareNumbers(left, right) == 0;
        }
        return Objects.equals(left, right);
    }

    static int compare(Object left, Object right, String operator) {
        if (isNumber(left) && isNumber(right)) {
            return compareNumbers(left, right);
        }
        if (left instanceof String leftText && right instanceof String rightText) {
            return leftText.compareTo(rightText);
        }
        throw new ExpressionEvaluationException("cannot apply '" + operator + "' to " + describe(left) + " and " + describe(right));
    }

    static int compareNumbers(Object left, Object right) {
        if (isIntegral(left) && isIntegral(right)) {
            return Long.compare(asLong(left), asLong(right));
        }
        return Double.compare(asDouble(left), asDouble(right));
    }

    static Object arithmetic(String operator, Object left, Object right) {
        if ("+".equals(operator) && (left instanceof String || right instanceof String)) {
            if (left == null || right == null) {
                throw new ExpressionEvaluationException("cannot concatenate null");
            }
            return String.valueOf(left) + right;
        }
        if (!isNumber(left) || !isNumber(right)) {
            throw new ExpressionEvaluationException("cannot apply '" + operator + "' to " + describe(left) + " and " + describe(right));
        }
        if (isIntegral(left) && isIntegral(right)) {
            return integralArithmetic(operator, asLong(left), asLong(right));
        }
        return decimalArithmetic(operator, asDouble(left), asDouble(right));
    }

    private static Object integralArithmetic(String operator, long left, long right) {
        return switch (operator) {
            case "+" -> Math.addExact(left, right);
            case "-" -> Math.subtractExact(left, right);
            case "*" -> Math.multiplyExact(left, right);
            case "/" -> {
                requireNonZero(right);
                yield left % right == 0 ? (Object) (left / right) : (Object) ((double) left / right);
            }
            case "%" -> {
                requireNonZero(right);
                yield left % right;
            }
            default -> throw new IllegalStateException(operator);
        };
    }

    private static Object decimalArithmetic(String operator, double left, double right) {
        return switch (operator) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> {
                requireNonZero(right);
                yield left / right;
            }
            case "%" -> {
                requireNonZero(right);
                yield left % right;
            }
            default -> throw new IllegalStateException(operator);
        };
    }

    private static void requireNonZero(double divisor) {
        if (divisor == 0) {
            throw new ExpressionEvaluationException("division by zero");
        }
    }

    static boolean contains(Object container, Object element) {
        return switch (container) {
            case null -> false;
            case String text when element instanceof String part -> text.contains(part);
            case Collection<?> items -> items.stream().anyMatch(item -> same(item, element));
            case Map<?, ?> map -> map.containsKey(element);
            default -> throw new ExpressionEvaluationException("'in' expects a list, map or string but got " + describe(container));
        };
    }

    static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
