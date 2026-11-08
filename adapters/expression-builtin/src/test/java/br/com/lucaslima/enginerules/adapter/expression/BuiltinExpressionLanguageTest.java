package br.com.lucaslima.enginerules.adapter.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionEvaluationException;
import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionSyntaxException;
import br.com.lucaslima.enginerules.domain.execution.spi.FieldReference;

class BuiltinExpressionLanguageTest {

    private static final Map<String, Object> CONTEXT = Map.of(
            "input", Map.of("age", 30, "name", "Ana", "tags", List.of("vip", "early"),
                    "orders", List.of(Map.of("total", 100), Map.of("total", 250.5))),
            "decision", Map.of("approved", true));

    private final BuiltinExpressionLanguage language = new BuiltinExpressionLanguage();

    @Test
    void respectsOperatorPrecedence() {
        assertEquals(14L, evaluate("2 + 3 * 4"));
        assertEquals(20L, evaluate("(2 + 3) * 4"));
        assertEquals(true, evaluate("1 + 1 == 2 && !false"));
        assertEquals(2.5, evaluate("5 / 2"));
        assertEquals(2L, evaluate("4 / 2"));
    }

    @Test
    void readsNestedFieldsAndIndexes() {
        assertEquals(true, evaluate("input.age >= 18 and decision.approved"));
        assertEquals("early", evaluate("input.tags[1]"));
        assertEquals("early", evaluate("input.tags[-1]"));
        assertEquals("Ana", evaluate("input[\"name\"]"));
    }

    @Test
    void treatsMissingValuesAsNullSafely() {
        assertNull(evaluate("input.address.street"));
        assertEquals(false, evaluate("input.missing > 10"));
        assertEquals(true, evaluate("input.missing == null"));
        assertNull(evaluate("upper(input.missing)"));
        assertEquals(false, evaluate("startsWith(input.missing, 'a')"));
    }

    @Test
    void supportsMembershipAndStrings() {
        assertEquals(true, evaluate("'vip' in input.tags"));
        assertEquals(true, evaluate("'gold' not in input.tags"));
        assertEquals("Ana Lima", evaluate("input.name + ' Lima'"));
        assertEquals(true, evaluate("startsWith(lower(input.name), 'an')"));
    }

    @Test
    void iteratesCollectionsWithTheCurrentElement() {
        assertEquals(350.5, evaluate("sum(map(input.orders, #.total))"));
        assertEquals(1L, evaluate("count(input.orders, #.total > 200)"));
        assertEquals(true, evaluate("all(input.orders, #.total > 50)"));
        assertEquals(List.of(Map.of("total", 100)), evaluate("filter(input.orders, #.total < 200)"));
    }

    @Test
    void buildsListAndMapLiterals() {
        assertEquals(Map.of("name", "Ana", "adult", true), evaluate("{name: input.name, 'adult': input.age >= 18}"));
        assertEquals(List.of(1L, 2L), evaluate("[1, 2]"));
        assertEquals("adult", evaluate("input.age >= 18 ? 'adult' : 'minor'"));
    }

    @Test
    void reportsFieldReferencesForCompileTimeGuards() {
        assertEquals(Set.of(new FieldReference("input", "age"), new FieldReference("decision", "approved")),
                language.compile("input.age > 1 && decision.approved").references());
        assertEquals(Set.of(FieldReference.rootOnly("results")), language.compile("len(results) > 0").references());
    }

    @Test
    void rejectsMalformedExpressionsAtCompileTime() {
        assertThrows(ExpressionSyntaxException.class, () -> language.compile("input.age >="));
        assertThrows(ExpressionSyntaxException.class, () -> language.compile("unknownFn(1)"));
        assertThrows(ExpressionSyntaxException.class, () -> language.compile("len(1, 2)"));
        assertThrows(ExpressionSyntaxException.class, () -> language.compile("'open"));
    }

    @Test
    void failsClearlyOnTypeErrors() {
        assertThrows(ExpressionEvaluationException.class, () -> evaluate("input.name > 3"));
        assertThrows(ExpressionEvaluationException.class, () -> evaluate("1 / 0"));
        assertThrows(ExpressionEvaluationException.class, () -> evaluate("input.age && true"));
    }

    private Object evaluate(String source) {
        return language.compile(source).evaluate(CONTEXT);
    }
}
