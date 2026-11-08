package br.com.lucaslima.enginerules.domain.execution;

public sealed interface ExecutionOutcome {

    record Responded(String state, int status, Object data) implements ExecutionOutcome {
    }

    record Failed(ExecutionErrorCode error, String message) implements ExecutionOutcome {
    }

    record PreviewBlocked(String state) implements ExecutionOutcome {
        public static final String CODE = "preview_blocked_write";
    }
}
