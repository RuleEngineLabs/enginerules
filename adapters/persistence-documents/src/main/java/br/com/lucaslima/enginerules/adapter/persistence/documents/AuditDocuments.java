package br.com.lucaslima.enginerules.adapter.persistence.documents;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;

public final class AuditDocuments {

    private AuditDocuments() {
    }

    public static Map<String, Object> fromRecord(AuditRecord record) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("occurredAt", record.occurredAt().toString());
        tree.put("policy", record.policy());
        tree.put("actor", record.actor());
        tree.put("action", record.action());
        tree.put("details", new LinkedHashMap<>(record.details()));
        return tree;
    }

    public static AuditRecord toRecord(Map<String, Object> tree) {
        Map<String, String> details = new LinkedHashMap<>();
        PolicyDocuments.object(tree.getOrDefault("details", Map.of()))
                .forEach((key, value) -> details.put(key, String.valueOf(value)));
        return new AuditRecord(Instant.parse((String) tree.get("occurredAt")), (String) tree.get("policy"),
                (String) tree.get("actor"), (String) tree.get("action"), details);
    }
}
