package br.com.lucaslima.enginerules.adapter.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionEvaluationException;
import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionSyntaxException;

class ExpressionSemanticsTest {

    private static final Map<String, Object> CONTEXT = context();

    private final BuiltinExpressionLanguage language = new BuiltinExpressionLanguage();

    @Test
    void evaluatesBuiltins() {
        Object[][] cases = {
                {"len('abc')", 3L}, {"len(input.tags)", 2L}, {"len(input)", 6L}, {"len(input.nothing)", 0L},
                {"contains(input.tags, 'vip')", true}, {"contains('banana', 'nan')", true},
                {"endsWith('policy', 'cy')", true}, {"endsWith(input.nothing, 'x')", false},
                {"trim('  x ')", "x"}, {"trim(input.nothing)", null}, {"lower(input.nothing)", null},
                {"has(input, 'tags')", true}, {"has('x', 'tags')", false},
                {"abs(-3)", 3L}, {"abs(-2.5)", 2.5}, {"min(3, 1, 2)", 1L}, {"max(input.numbers)", 9},
                {"string(12)", "12"}, {"string(input.nothing)", null},
                {"int('42')", 42L}, {"int(3.9)", 3L}, {"float('2.5')", 2.5}, {"float(2)", 2.0},
                {"any(input.numbers, # > 8)", true}, {"none(input.numbers, # > 100)", true},
                {"count(input.nothing, # > 1)", 0L}, {"sum(input.numbers)", 15L}, {"min(input.decimal, 2)", 2L},
        };
        for (Object[] testCase : cases) {
            assertEquals(testCase[1], evaluate((String) testCase[0]), () -> "for " + testCase[0]);
        }
    }

    @Test
    void evaluatesOperators() {
        Object[][] cases = {
                {"5 - 2", 3L}, {"7 % 4", 3L}, {"2.5 - 1", 1.5}, {"2.0 * 3", 6.0}, {"7.5 / 2.5", 3.0}, {"7.5 % 2", 1.5},
                {"1 != 2", true}, {"'a' != 'a'", false}, {"2 <= 2", true}, {"'b' > 'a'", true}, {"false || true", true},
                {"true or false", true}, {"not false", true}, {"-(2.5)", -2.5}, {"1e2", 100.0}, {"1.5E-1", 0.15},
                {"'tab\\t' + \"q\\\"\" + 'n\\n' + 'r\\r' + 'b\\\\'", "tab\tq\"n\nr\rb\\"}, {"'x' in {x: 1}", true},
                {"input.tags[5]", null}, {"input.nothing[0]", null}, {"input.tags[input.nothing]", null},
                {"input.big + 1", 11L}, {"input.decimal * 2", 5.0}, {"input.one == 1.0", true},
        };
        for (Object[] testCase : cases) {
            assertEquals(testCase[1], evaluate((String) testCase[0]), () -> "for " + testCase[0]);
        }
    }

    @Test
    void rejectsTypeErrorsAtRuntime() {
        String[] failures = {
                "len(5)", "abs('x')", "min('a')", "int('x')", "int(true)", "float('x')", "float(true)", "upper(1)",
                "filter('x', # > 1)", "'a' + input.nothing", "true - 1", "1 % 0", "2.5 / 0", "2.5 % 0",
                "5 in 3", "input.tags.name", "input.tags[true]", "-'x'", "9223372036854775807 + 1", "'a' < 1",
        };
        for (String source : failures) {
            assertThrows(ExpressionEvaluationException.class, () -> evaluate(source), () -> "for " + source);
        }
    }

    @Test
    void rejectsMalformedSyntax() {
        String[] failures = {
                "1 @ 2", "1e", "99999999999999999999", "'unterminated\\", "'bad \\q escape'", "(1", "1 2", "[1",
                "{1: 2}", "{a 2}", "input.", "null(1)", ")", "",
        };
        for (String source : failures) {
            assertThrows(ExpressionSyntaxException.class, () -> language.compile(source), () -> "for " + source);
        }
    }

    @Test
    void handlesNullsAndMixedNumbersSafely() {
        assertNull(evaluate("input.nothing.deeper"));
        assertEquals(false, evaluate("input.nothing < 3"));
        assertEquals(false, evaluate("null in input.tags"));
        assertEquals(false, evaluate("'x' in input.nothing"));
        assertTrue(Values.isIntegral(BigInteger.TEN));
        assertEquals(1.5, Values.asDouble(new BigDecimal("1.5")));
        assertEquals("null", Values.describe(null));
    }

    private Object evaluate(String source) {
        return language.compile(source).evaluate(CONTEXT);
    }

    private static Map<String, Object> context() {
        Map<String, Object> input = new HashMap<>();
        input.put("tags", List.of("vip", "early"));
        input.put("numbers", Arrays.asList(1, 5, 9));
        input.put("big", BigInteger.TEN);
        input.put("decimal", new BigDecimal("2.5"));
        input.put("one", 1);
        input.put("nothing", null);
        return Map.of("input", input);
    }
}
