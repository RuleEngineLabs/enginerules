package br.com.lucaslima.enginerules.adapter.web.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.execution.ExecutionErrorCode;
import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;

class ExecutionResponsesTest {

    @Test
    void authoredResponsesUseTheStateEnvelopeAndLiteralStatus() {
        ExecutionOutcome outcome = new ExecutionOutcome.Responded("approved", 201, Map.of("limit", 1000));

        assertEquals(201, ExecutionResponses.httpStatus(outcome));
        assertEquals(Map.of("state", "approved", "data", Map.of("limit", 1000)), ExecutionResponses.envelope(outcome));
    }

    @Test
    void executorFailuresUseTheErrorEnvelopeWithoutState() {
        ExecutionOutcome outcome = new ExecutionOutcome.Failed(ExecutionErrorCode.INVALID_PARALLEL_SOURCE, "null item");

        Map<String, Object> body = ExecutionResponses.envelope(outcome);

        assertEquals(500, ExecutionResponses.httpStatus(outcome));
        assertEquals("invalid_parallel_source", body.get("error"));
        assertFalse(body.containsKey("state"));
    }
}
