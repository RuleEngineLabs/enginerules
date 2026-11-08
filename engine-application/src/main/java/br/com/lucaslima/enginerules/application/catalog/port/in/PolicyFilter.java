package br.com.lucaslima.enginerules.application.catalog.port.in;

import java.util.Optional;

public record PolicyFilter(Optional<String> namePrefix, Optional<String> ownerGroup, Optional<Boolean> withPendingCandidate) {

    public static PolicyFilter of(String namePrefix, String ownerGroup, Boolean withPendingCandidate) {
        return new PolicyFilter(nonBlank(namePrefix), nonBlank(ownerGroup), Optional.ofNullable(withPendingCandidate));
    }

    private static Optional<String> nonBlank(String value) {
        return Optional.ofNullable(value).filter(text -> !text.isBlank());
    }
}
