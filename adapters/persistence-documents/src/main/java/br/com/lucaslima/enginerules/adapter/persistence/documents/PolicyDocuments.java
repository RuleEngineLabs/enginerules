package br.com.lucaslima.enginerules.adapter.persistence.documents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicySnapshot;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionEntry;
import br.com.lucaslima.enginerules.domain.definition.PolicyDocument;

public final class PolicyDocuments {

    private PolicyDocuments() {
    }

    public static Map<String, Object> fromSnapshot(PolicySnapshot snapshot, long revision) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("name", snapshot.name().value());
        tree.put("ownerGroup", snapshot.ownerGroup());
        tree.put("approvers", sorted(snapshot.approvers()));
        tree.put("allowedConsumers", sorted(snapshot.allowedConsumers()));
        tree.put("stable", snapshot.stable().toString());
        tree.put("pendingCandidate", snapshot.pendingCandidate() == null ? null : snapshot.pendingCandidate().toString());
        tree.put("stableGeneration", snapshot.stableGeneration());
        tree.put("createdAt", snapshot.createdAt().toString());
        tree.put("updatedAt", snapshot.updatedAt().toString());
        tree.put("revision", revision);
        List<Object> versions = new ArrayList<>();
        snapshot.versions().forEach(entry -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version", entry.version().toString());
            item.put("hash", entry.hash().value());
            item.put("author", entry.author());
            item.put("createdAt", entry.createdAt().toString());
            item.put("removedAt", entry.removedAt() == null ? null : entry.removedAt().toString());
            versions.add(item);
        });
        tree.put("versions", versions);
        return tree;
    }

    public static PolicySnapshot toSnapshot(Map<String, Object> tree) {
        List<VersionEntry> versions = new ArrayList<>();
        for (Object raw : list(tree.get("versions"))) {
            Map<String, Object> item = object(raw);
            versions.add(new VersionEntry(
                    SemanticVersion.parse((String) item.get("version")),
                    new ContentHash((String) item.get("hash")),
                    (String) item.get("author"),
                    Instant.parse((String) item.get("createdAt")),
                    item.get("removedAt") == null ? null : Instant.parse((String) item.get("removedAt"))));
        }
        return new PolicySnapshot(
                PolicyName.of((String) tree.get("name")),
                (String) tree.get("ownerGroup"),
                strings(tree.get("approvers")),
                strings(tree.get("allowedConsumers")),
                SemanticVersion.parse((String) tree.get("stable")),
                tree.get("pendingCandidate") == null ? null : SemanticVersion.parse((String) tree.get("pendingCandidate")),
                ((Number) tree.get("stableGeneration")).longValue(),
                versions,
                Instant.parse((String) tree.get("createdAt")),
                Instant.parse((String) tree.get("updatedAt")),
                ((Number) tree.get("revision")).longValue());
    }

    public static Map<String, Object> fromVersion(PolicyVersion version) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("name", version.name().value());
        tree.put("version", version.version().toString());
        tree.put("hash", version.hash().value());
        tree.put("definition", PolicyDocument.write(version.definition()));
        return tree;
    }

    public static PolicyVersion toVersion(Map<String, Object> tree) {
        return new PolicyVersion(
                PolicyName.of((String) tree.get("name")),
                SemanticVersion.parse((String) tree.get("version")),
                PolicyDocument.read(tree.get("definition")),
                new ContentHash((String) tree.get("hash")));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("corrupted store document: expected an object");
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    private static Set<String> strings(Object value) {
        Set<String> result = new LinkedHashSet<>();
        list(value).forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
