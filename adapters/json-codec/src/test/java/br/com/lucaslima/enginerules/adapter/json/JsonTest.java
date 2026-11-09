package br.com.lucaslima.enginerules.adapter.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesNestedDocuments() {
        Object parsed = Json.parse("{\"a\": [1, 2.5, true, null, \"x\\n\\u00e9\"], \"b\": {}}");

        assertEquals(Map.of("a", Arrays.asList(1L, 2.5, true, null, "x\né"), "b", Map.of()), parsed);
    }

    @Test
    void roundTrips() {
        Map<String, Object> document = Map.of("name", "Ana \"Lima\"", "items", List.of(1L, 2L), "nested", Map.of("ok", false));

        assertEquals(document, Json.parse(Json.write(document)));
    }

    @Test
    void rejectsTrailingContent() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} {}"));
    }
}
