package br.com.lucaslima.enginerules.domain.execution;

public enum ExecutionErrorCode {
    MISSING_CONTEXT_FIELD("missing_context_field"),
    INVALID_PARALLEL_SOURCE("invalid_parallel_source"),
    INVALID_REQUEST_PARAMETER("invalid_request_parameter"),
    EXPRESSION_ERROR("expression_error"),
    STEP_BUDGET_EXCEEDED("step_budget_exceeded"),
    DEADLINE_EXCEEDED("deadline_exceeded"),
    INTERRUPTED("interrupted");

    private final String code;

    ExecutionErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
