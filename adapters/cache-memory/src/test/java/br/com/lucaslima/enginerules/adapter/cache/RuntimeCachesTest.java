package br.com.lucaslima.enginerules.adapter.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionBump;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;

class RuntimeCachesTest {

    private static final Instant START = Instant.parse("2026-11-07T12:00:00Z");
    private static final PolicyName NAME = PolicyName.of("CreditLimit");
    private static final Actor ANA = new Actor("ana", Set.of("credit-team"));

    @Test
    void catalogCachesWithinTheRefreshIntervalAndHonoursDirectReads() {
        TickingClock clock = new TickingClock();
        AtomicReference<Policy> source = new AtomicReference<>(policy());
        AtomicInteger loads = new AtomicInteger();
        LoadPolicyPort origin = (name, consistency) -> {
            loads.incrementAndGet();
            return Optional.ofNullable(source.get()).map(policy -> Policy.restore(policy.snapshot()));
        };
        CachingPolicyCatalog catalog = new CachingPolicyCatalog(origin, clock, Duration.ofSeconds(30));

        catalog.loadPolicy(NAME, ReadConsistency.CACHED);
        catalog.loadPolicy(NAME, ReadConsistency.CACHED);
        assertEquals(1, loads.get());
        Policy promoted = policy();
        SemanticVersion candidate = promoted.publish(ANA, SemanticVersion.FIRST, hash("b"), VersionBump.MINOR, START).version();
        promoted.moveStable(ANA, candidate, START);
        source.set(promoted);
        assertEquals(candidate, catalog.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow().stable());
        assertEquals(candidate, catalog.loadPolicy(NAME, ReadConsistency.CACHED).orElseThrow().stable());
        source.set(policy());
        assertEquals(SemanticVersion.FIRST, catalog.loadPolicy(NAME, ReadConsistency.DIRECT).orElseThrow().stable());
        assertEquals(candidate, catalog.loadPolicy(NAME, ReadConsistency.CACHED).orElseThrow().stable());
        clock.advance(Duration.ofSeconds(31));
        source.set(null);
        assertEquals(Optional.empty(), catalog.loadPolicy(NAME, ReadConsistency.CACHED));
    }

    @Test
    void rolloutLookupRefreshesAndFailsOpen() {
        TickingClock clock = new TickingClock();
        AtomicReference<RuntimeException> outage = new AtomicReference<>();
        AtomicInteger loads = new AtomicInteger();
        Canary canary = Canary.start("c-1", NAME, SemanticVersion.parse("1.1.0"), SemanticVersion.FIRST, 10, null,
                CanaryCriteria.GLOBAL_DEFAULT, Optional.empty(), "ana", START);
        Shadow shadow = Shadow.start("s-1", NAME, SemanticVersion.parse("1.1.0"), 10, null, List.of(), List.of(), "ana", START);
        CachingRolloutLookup lookup = new CachingRolloutLookup(
                (name, consistency) -> {
                    loads.incrementAndGet();
                    if (outage.get() != null) {
                        throw outage.get();
                    }
                    return Optional.of(canary);
                },
                (name, consistency) -> Optional.of(shadow), clock, Duration.ofSeconds(5));

        assertEquals("c-1", lookup.findOpen(NAME, ReadConsistency.CACHED).orElseThrow().id());
        lookup.findOpen(NAME, ReadConsistency.CACHED);
        assertEquals(1, loads.get());
        outage.set(new IllegalStateException("store down"));
        clock.advance(Duration.ofSeconds(6));
        assertEquals("c-1", lookup.findOpen(NAME, ReadConsistency.CACHED).orElseThrow().id());
        assertEquals(Optional.empty(), lookup.findOpen(PolicyName.of("Other"), ReadConsistency.DIRECT));
        assertEquals("s-1", lookup.findCurrent(NAME, ReadConsistency.DIRECT).orElseThrow().id());
    }

    @Test
    void resultCacheHonoursReaderTtlAndRetention() {
        TickingClock clock = new TickingClock();
        InMemoryResultCache cache = new InMemoryResultCache(clock, Duration.ofMinutes(1));
        cache.store("sig", ApiResponse.of(200, "x", Map.of()));

        assertTrue(cache.find("sig", Duration.ofSeconds(10)).isPresent());
        clock.advance(Duration.ofSeconds(20));
        assertFalse(cache.find("sig", Duration.ofSeconds(10)).isPresent());
        assertTrue(cache.find("sig", Duration.ofSeconds(30)).isPresent());
        clock.advance(Duration.ofMinutes(2));
        assertFalse(cache.find("sig", Duration.ofHours(1)).isPresent());
        assertFalse(cache.find("missing", Duration.ofHours(1)).isPresent());
    }

    @Test
    void compiledCachePropagatesCompilationFailuresAndValidatesSize() {
        InMemoryCompiledPolicyCache cache = new InMemoryCompiledPolicyCache(2);

        assertThrows(IllegalStateException.class, () -> cache.getOrCompile(NAME, SemanticVersion.FIRST, () -> {
            throw new IllegalStateException("broken");
        }));
        assertEquals(0, cache.size());
        assertThrows(IllegalArgumentException.class, () -> new InMemoryCompiledPolicyCache(0));
    }

    @Test
    void concurrentCallersWaitForTheSameCompilation() throws InterruptedException {
        InMemoryCompiledPolicyCache cache = new InMemoryCompiledPolicyCache(2);
        CountDownLatch compiling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompiledPolicy compiled = new CompiledPolicy("done", Map.of(), List.of());
        AtomicReference<CompiledPolicy> waiter = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> cache.getOrCompile(NAME, SemanticVersion.FIRST, () -> {
            compiling.countDown();
            await(release);
            return compiled;
        }));
        compiling.await(1, TimeUnit.SECONDS);
        Thread second = Thread.ofVirtual().start(() -> waiter.set(cache.getOrCompile(NAME, SemanticVersion.FIRST, () -> null)));
        Thread.sleep(50);
        release.countDown();
        first.join();
        second.join();

        assertEquals(compiled, waiter.get());
    }

    @Test
    void concurrentCallersSeeTheSameCompilationFailure() throws InterruptedException {
        InMemoryCompiledPolicyCache cache = new InMemoryCompiledPolicyCache(2);
        CountDownLatch compiling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                cache.getOrCompile(NAME, SemanticVersion.FIRST, () -> {
                    compiling.countDown();
                    await(release);
                    throw new IllegalStateException("broken");
                });
            } catch (IllegalStateException expected) {
                seen.compareAndSet(null, expected);
            }
        });
        compiling.await(1, TimeUnit.SECONDS);
        Thread second = Thread.ofVirtual().start(() -> {
            try {
                cache.getOrCompile(NAME, SemanticVersion.FIRST, () -> null);
            } catch (RuntimeException failure) {
                seen.set(failure);
            }
        });
        Thread.sleep(50);
        release.countDown();
        first.join();
        second.join();

        assertTrue(seen.get() instanceof IllegalStateException);
    }

    @Test
    void shadowQueueRejectsWhenFullAndTogglesResolvePerPolicy() throws InterruptedException {
        BoundedShadowQueue queue = new BoundedShadowQueue(1, 1);
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Runnable blocking = () -> {
            await(block);
            done.countDown();
        };

        assertTrue(queue.offer(blocking));
        assertTrue(queue.offer(done::countDown));
        assertFalse(queue.offer(() -> { }));
        assertEquals(1, queue.dropped());
        assertEquals(1, queue.queued());
        block.countDown();
        assertTrue(done.await(1, TimeUnit.SECONDS));
        queue.close();
        assertTrue(new StaticExecutionToggles(false, Set.of("CreditLimit")).traceEnabledFor(NAME));
        assertFalse(new StaticExecutionToggles(false, Set.of()).traceEnabledFor(NAME));
        assertTrue(new StaticExecutionToggles(true, Set.of()).traceEnabledFor(NAME));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Policy policy() {
        return Policy.create(NAME, "credit-team", Set.of(), hash("a"), ANA, START);
    }

    private static ContentHash hash(String seed) {
        return new ContentHash(seed.repeat(64));
    }

    private static final class TickingClock extends Clock {
        private Instant now = START;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
