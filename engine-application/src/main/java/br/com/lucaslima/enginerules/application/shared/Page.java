package br.com.lucaslima.enginerules.application.shared;

import java.util.List;
import java.util.function.Function;

public record Page<T>(List<T> items, int pageNumber, int pageSize, long totalItems) {

    public Page {
        items = List.copyOf(items);
    }

    public static <T> Page<T> slice(List<T> all, PageRequest request) {
        int from = Math.min(request.offset(), all.size());
        int to = Math.min(from + request.pageSize(), all.size());
        return new Page<>(all.subList(from, to), request.pageNumber(), request.pageSize(), all.size());
    }

    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), pageNumber, pageSize, totalItems);
    }
}
