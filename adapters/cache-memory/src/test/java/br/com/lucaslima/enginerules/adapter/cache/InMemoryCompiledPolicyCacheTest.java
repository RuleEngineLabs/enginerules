package br.com.lucaslima.enginerules.adapter.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;

class InMemoryCompiledPolicyCacheTest {

    private static final PolicyName NAME = PolicyName.of("CreditLimit");

    @Test
    void compilesEachVersionOnce() {
        InMemoryCompiledPolicyCache cache = new InMemoryCompiledPolicyCache(10);
        AtomicInteger compilations = new AtomicInteger();
        CompiledPolicy compiled = new CompiledPolicy("done", Map.of(), List.of());

        CompiledPolicy first = cache.getOrCompile(NAME, SemanticVersion.FIRST, () -> {
            compilations.incrementAndGet();
            return compiled;
        });
        CompiledPolicy second = cache.getOrCompile(NAME, SemanticVersion.FIRST, () -> {
            compilations.incrementAndGet();
            return compiled;
        });

        assertSame(first, second);
        assertEquals(1, compilations.get());
    }

    @Test
    void evictsTheOldestEntriesBeyondCapacity() {
        InMemoryCompiledPolicyCache cache = new InMemoryCompiledPolicyCache(2);
        for (int patch = 0; patch < 5; patch++) {
            cache.getOrCompile(NAME, new SemanticVersion(1, 0, patch), () -> new CompiledPolicy("done", Map.of(), List.of()));
        }

        assertEquals(2, cache.size());
    }
}
