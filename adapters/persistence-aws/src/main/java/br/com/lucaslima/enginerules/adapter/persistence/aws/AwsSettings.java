package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public record AwsSettings(String region, Optional<URI> endpoint, String tablePrefix, String snapshotBucket) {

    public AwsSettings {
        Objects.requireNonNull(region, "region");
        endpoint = endpoint == null ? Optional.empty() : endpoint;
        tablePrefix = tablePrefix == null ? "" : tablePrefix;
        Objects.requireNonNull(snapshotBucket, "snapshotBucket");
    }

    public AwsTables tables() {
        return AwsTables.withPrefix(tablePrefix);
    }
}
