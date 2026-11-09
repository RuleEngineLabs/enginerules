package br.com.lucaslima.enginerules.adapter.cache;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import br.com.lucaslima.enginerules.application.execution.port.out.CompiledPolicyCachePort;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;

public final class InMemoryCompiledPolicyCache implements CompiledPolicyCachePort {

    private final int maxEntries;
    private final Map<String, CompletableFuture<CompiledPolicy>> entries = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();

    public InMemoryCompiledPolicyCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public CompiledPolicy getOrCompile(PolicyName name, SemanticVersion version, Supplier<CompiledPolicy> compilation) {
        String key = name.value() + "@" + version;
        CompletableFuture<CompiledPolicy> created = new CompletableFuture<>();
        CompletableFuture<CompiledPolicy> existing = entries.putIfAbsent(key, created);
        if (existing != null) {
            return join(existing);
        }
        try {
            created.complete(compilation.get());
            insertionOrder.add(key);
            evictBeyondCapacity();
        } catch (RuntimeException failure) {
            entries.remove(key, created);
            created.completeExceptionally(failure);
            throw failure;
        }
        return join(created);
    }

    public int size() {
        return entries.size();
    }

    private void evictBeyondCapacity() {
        Iterator<String> oldest = insertionOrder.iterator();
        while (entries.size() > maxEntries && oldest.hasNext()) {
            String key = oldest.next();
            oldest.remove();
            entries.remove(key);
        }
    }

    private static CompiledPolicy join(CompletableFuture<CompiledPolicy> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            throw failure.getCause() instanceof RuntimeException runtime ? runtime : failure;
        }
    }
}
