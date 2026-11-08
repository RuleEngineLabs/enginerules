package br.com.lucaslima.enginerules.domain.rollout;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.zip.CRC32C;

public final class TrafficSelector {

    private static final int BUCKETS = 10_000;

    private TrafficSelector() {
    }

    public static boolean selects(Optional<String> routingKey, String salt, int percentage, RandomGenerator random) {
        if (percentage <= 0) {
            return false;
        }
        if (percentage >= 100) {
            return true;
        }
        int bucket = routingKey.filter(key -> !key.isBlank())
                .map(key -> stableBucket(salt + ":" + key))
                .orElseGet(() -> random.nextInt(BUCKETS));
        return bucket < percentage * (BUCKETS / 100);
    }

    private static int stableBucket(String value) {
        CRC32C checksum = new CRC32C();
        checksum.update(value.getBytes(StandardCharsets.UTF_8));
        return (int) (checksum.getValue() % BUCKETS);
    }
}
