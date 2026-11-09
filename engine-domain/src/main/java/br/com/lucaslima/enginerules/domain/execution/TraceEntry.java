package br.com.lucaslima.enginerules.domain.execution;

import java.util.List;
import java.util.Map;

public record TraceEntry(
        String state,
        String kind,
        String next,
        long durationMicros,
        List<Attempt> attempts,
        boolean resultFromCache,
        Map<String, List<Integer>> failedItemsBySignature) {

    public TraceEntry {
        attempts = List.copyOf(attempts);
        failedItemsBySignature = Map.copyOf(failedItemsBySignature);
    }

    public record Attempt(Integer status, String error, boolean timeout, long durationMicros) {
    }
}
