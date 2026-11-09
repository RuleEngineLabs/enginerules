package br.com.lucaslima.enginerules.domain.execution;

import java.time.Duration;
import java.util.List;

public record ExecutionReport(ExecutionOutcome outcome, List<TraceEntry> trace, int steps, Duration elapsed) {

    public ExecutionReport {
        trace = List.copyOf(trace);
    }
}
