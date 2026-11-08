package br.com.lucaslima.enginerules.adapter.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class JsonEdgeCasesTest {

    private enum Colour { RED }

    @Test
    void writesSpecialValues() {
        assertEquals("[null,null,null,\"2026-11-07T12:00:00Z\",1,null,\"RED\",\"raw\"]", Json.write(Arrays.asList(
                null, Double.NaN, Float.POSITIVE_INFINITY, Instant.parse("2026-11-07T12:00:00Z"), Optional.of(1),
                Optional.empty(), Colour.RED, new StringBuilder("raw"))));
        assertEquals("\"\\\\ \\n \\r \\t \\b \\f \\u0001\"", Json.write("\\ \n \r \t \b \f \u0001"));
    }

    @Test
    void parsesEscapesNumbersAndEmptyContainers() {
        assertEquals("\" \\ / \b \f \n \r \t é", Json.parse("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u00e9\""));
        assertEquals(List.of(), Json.parse(" [ ] "));
        assertEquals(Map.of(), Json.parse("{}"));
        assertEquals(-2.5e3, Json.parse("-2.5e3"));
        assertEquals(new BigDecimal("123456789012345678901234567890"), Json.parse("123456789012345678901234567890"));
        assertEquals(Map.of("a", List.of(Map.of("b", false))), Json.parse("{\"a\":[{\"b\":false}]}"));
    }

    @Test
    void reportsMalformedDocuments() {
        String[] invalid = {"", "@", "{1:2}", "{\"a\" 1}", "[1 2]", "\"abc", "\"\\u12\"", "\"\\x\"", "tru", "-", "{\"a\":1,}", "\"\\"};
        for (String text : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(text), () -> "for " + text);
        }
    }
}
