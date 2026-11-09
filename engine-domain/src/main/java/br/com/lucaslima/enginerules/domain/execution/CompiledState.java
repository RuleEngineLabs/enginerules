package br.com.lucaslima.enginerules.domain.execution;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.lucaslima.enginerules.domain.definition.StateKind;
import br.com.lucaslima.enginerules.domain.execution.spi.Protocol;

public sealed interface CompiledState {

    String id();

    StateKind kind();

    sealed interface Routed extends CompiledState {
        Optional<String> contextKey();

        List<CompiledTransition> transitions();

        String fallback();
    }

    record ExecutionStep(
            String id,
            ValueTemplate.ObjectTemplate output,
            Optional<String> contextKey,
            List<CompiledTransition> transitions,
            String fallback) implements Routed {

        @Override
        public StateKind kind() {
            return StateKind.EXECUTION;
        }
    }

    record ApiCallStep(
            String id,
            Protocol protocol,
            String connection,
            String method,
            String pathTemplate,
            Map<String, GuardedExpression> pathParams,
            Map<String, GuardedExpression> query,
            Optional<ValueTemplate> body,
            Duration timeout,
            RetryRule retry,
            Optional<Duration> resultCacheTtl,
            boolean readOnly,
            Optional<String> contextKey,
            List<CompiledTransition> transitions,
            String fallback) implements Routed {

        @Override
        public StateKind kind() {
            return StateKind.API_CALL;
        }

        public boolean writes() {
            return !readOnly;
        }
    }

    record ParallelStep(
            String id,
            GuardedExpression over,
            String each,
            int maxConcurrency,
            Optional<String> contextKey,
            List<CompiledTransition> transitions,
            String fallback) implements Routed {

        @Override
        public StateKind kind() {
            return StateKind.PARALLEL;
        }
    }

    record ResponseStep(String id, int status, Optional<ValueTemplate> data) implements CompiledState {

        @Override
        public StateKind kind() {
            return StateKind.RESPONSE;
        }
    }
}
