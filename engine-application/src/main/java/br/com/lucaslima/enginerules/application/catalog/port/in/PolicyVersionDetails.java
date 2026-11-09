package br.com.lucaslima.enginerules.application.catalog.port.in;

import java.time.Instant;

import br.com.lucaslima.enginerules.domain.catalog.VersionStatus;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;

public record PolicyVersionDetails(
        String name,
        String version,
        VersionStatus status,
        String hash,
        String author,
        Instant createdAt,
        PolicyDefinition definition) {
}
