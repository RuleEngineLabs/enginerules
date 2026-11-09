package br.com.lucaslima.enginerules.domain.catalog;

import static br.com.lucaslima.enginerules.domain.fixtures.Policies.ageGate;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.execution;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.policy;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.response;
import static br.com.lucaslima.enginerules.domain.fixtures.Policies.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;

class ChangeClassifierTest {

    private final ChangeClassifier classifier = new ChangeClassifier();

    @Test
    void identicalContentIsNoChange() {
        assertEquals(VersionBump.NONE, classifier.classify(ageGate(), ageGate()));
    }

    @Test
    void changingAnExpressionIsPatch() {
        assertEquals(VersionBump.PATCH, classifier.classify(ageGate(), gate("input.age >= 21", "approved", 403)));
    }

    @Test
    void changingATransitionTargetIsMinor() {
        assertEquals(VersionBump.MINOR, classifier.classify(ageGate(), gate("input.age >= 18", "denied", 403)));
    }

    @Test
    void changingAResponseStatusIsMajor() {
        assertEquals(VersionBump.MAJOR, classifier.classify(ageGate(), gate("input.age >= 18", "approved", 422)));
    }

    private static PolicyDefinition gate(String condition, String target, int deniedStatus) {
        return policy("evaluate",
                execution("evaluate")
                        .map(Map.of("adult", condition))
                        .contextKey("decision")
                        .transitions(when("decision.adult", target))
                        .fallback("denied")
                        .build(),
                response("approved", 200, Map.of("approved", "true", "name", "input.name")),
                response("denied", deniedStatus, Map.of("approved", "false")));
    }
}
