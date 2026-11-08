package br.com.lucaslima.enginerules.application.catalog.port.in;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import br.com.lucaslima.enginerules.domain.catalog.PolicyEvent;

public record AuditRecord(Instant occurredAt, String policy, String actor, String action, Map<String, String> details) {

    public AuditRecord {
        details = Map.copyOf(details);
    }

    public static AuditRecord from(PolicyEvent event) {
        Map<String, String> details = new LinkedHashMap<>();
        String action = switch (event) {
            case PolicyEvent.PolicyCreated created -> {
                details.put("owner", created.owner());
                details.put("version", created.version().toString());
                yield "policy.created";
            }
            case PolicyEvent.VersionPublished published -> {
                details.put("version", published.version().toString());
                details.put("basedOn", published.basedOn().toString());
                details.put("bump", published.bump().name());
                yield "version.published";
            }
            case PolicyEvent.StableVersionMoved moved -> {
                details.put("from", moved.from().toString());
                details.put("to", moved.to().toString());
                details.put("kind", moved.kind().name());
                moved.discardedCandidate().ifPresent(version -> details.put("discardedCandidate", version.toString()));
                yield "stable.moved";
            }
            case PolicyEvent.VersionRemoved removed -> {
                details.put("version", removed.version().toString());
                yield "version.removed";
            }
        };
        return new AuditRecord(event.occurredAt(), event.policy().value(), event.actor(), action, details);
    }
}
