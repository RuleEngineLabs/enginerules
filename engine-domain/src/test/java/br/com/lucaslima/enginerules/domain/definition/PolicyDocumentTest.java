package br.com.lucaslima.enginerules.domain.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.fixtures.Policies;

class PolicyDocumentTest {

    @Test
    void roundTripsThroughTheTreeForm() {
        PolicyDefinition original = Policies.ageGate();

        assertEquals(original, PolicyDocument.read(PolicyDocument.write(original)));
    }

    @Test
    void rejectsUnknownFields() {
        Map<String, Object> tree = Map.of("entry", "done", "states",
                List.of(Map.of("id", "done", "kind", "response", "status", 200, "colour", "blue")));

        InvalidPolicyDocumentException failure = assertThrows(InvalidPolicyDocumentException.class,
                () -> PolicyDocument.read(tree));

        assertTrue(failure.problems().contains("state 'done': unknown field 'colour'"));
    }

    @Test
    void hashIgnoresFieldOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("entry", "done");
        first.put("states", List.of(Map.of("id", "done", "kind", "response", "status", 200)));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("states", List.of(Map.of("status", 200, "kind", "response", "id", "done")));
        second.put("entry", "done");

        assertEquals(ContentHash.of(PolicyDocument.read(first)), ContentHash.of(PolicyDocument.read(second)));
    }

    @Test
    void canonicalFormNormalisesNumberRepresentation() {
        assertEquals(CanonicalForm.of(Map.of("value", 1)), CanonicalForm.of(Map.of("value", 1.0)));
    }
}
