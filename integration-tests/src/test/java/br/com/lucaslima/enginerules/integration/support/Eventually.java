package br.com.lucaslima.enginerules.integration.support;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class Eventually {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private Eventually() {
    }

    public static <T> T await(Supplier<T> probe, Predicate<T> condition, String description) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        T last = probe.get();
        while (!condition.test(last)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + description + "; last value: " + last);
            }
            pause();
            last = probe.get();
        }
        return last;
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
