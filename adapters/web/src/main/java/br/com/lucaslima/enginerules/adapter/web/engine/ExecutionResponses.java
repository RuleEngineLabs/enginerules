package br.com.lucaslima.enginerules.adapter.web.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;
import br.com.lucaslima.enginerules.domain.execution.ExecutionReport;
import br.com.lucaslima.enginerules.domain.execution.TraceEntry;

public final class ExecutionResponses {

    private ExecutionResponses() {
    }

    public static int httpStatus(ExecutionOutcome outcome) {
        return switch (outcome) {
            case ExecutionOutcome.Responded responded -> responded.status();
            case ExecutionOutcome.Failed ignored -> 500;
            case ExecutionOutcome.PreviewBlocked ignored -> 200;
        };
    }

    public static Map<String, Object> envelope(ExecutionOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (outcome) {
            case ExecutionOutcome.Responded responded -> {
                body.put("state", responded.state());
                body.put("data", responded.data());
            }
            case ExecutionOutcome.Failed failed -> {
                body.put("error", failed.error().code());
                body.put("message", failed.message());
            }
            case ExecutionOutcome.PreviewBlocked blocked -> {
                body.put("result", ExecutionOutcome.PreviewBlocked.CODE);
                body.put("state", blocked.state());
            }
        }
        return body;
    }

    public static Map<String, Object> preview(List<String> writeStates, ExecutionReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("writeStates", writeStates);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("type", outcomeType(report.outcome()));
        outcome.put("status", httpStatus(report.outcome()));
        outcome.put("body", envelope(report.outcome()));
        body.put("outcome", outcome);
        body.put("steps", report.steps());
        body.put("elapsedMicros", report.elapsed().toNanos() / 1_000);
        body.put("trace", trace(report.trace()));
        return body;
    }

    public static List<Map<String, Object>> trace(List<TraceEntry> entries) {
        return entries.stream().map(ExecutionResponses::traceEntry).toList();
    }

    private static String outcomeType(ExecutionOutcome outcome) {
        return switch (outcome) {
            case ExecutionOutcome.Responded ignored -> "responded";
            case ExecutionOutcome.Failed ignored -> "failed";
            case ExecutionOutcome.PreviewBlocked ignored -> ExecutionOutcome.PreviewBlocked.CODE;
        };
    }

    private static Map<String, Object> traceEntry(TraceEntry entry) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("state", entry.state());
        tree.put("kind", entry.kind());
        tree.put("next", entry.next());
        tree.put("durationMicros", entry.durationMicros());
        if (!entry.attempts().isEmpty()) {
            tree.put("attempts", entry.attempts().stream().map(attempt -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("status", attempt.status());
                item.put("error", attempt.error());
                item.put("timeout", attempt.timeout());
                item.put("durationMicros", attempt.durationMicros());
                return item;
            }).toList());
        }
        if (entry.resultFromCache()) {
            tree.put("resultFromCache", true);
        }
        if (!entry.failedItemsBySignature().isEmpty()) {
            tree.put("failedItems", entry.failedItemsBySignature());
        }
        return tree;
    }
}
