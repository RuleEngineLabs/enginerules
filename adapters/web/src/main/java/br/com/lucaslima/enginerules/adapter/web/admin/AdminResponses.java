package br.com.lucaslima.enginerules.adapter.web.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicySummary;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyVersionDetails;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishedVersion;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.domain.catalog.StableMove;
import br.com.lucaslima.enginerules.domain.definition.PolicyDocument;

final class AdminResponses {

    private AdminResponses() {
    }

    static Map<String, Object> policy(PolicySummary summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", summary.name());
        body.put("ownerGroup", summary.ownerGroup());
        body.put("stableVersion", summary.stableVersion());
        body.put("pendingCandidate", summary.pendingCandidate().orElse(null));
        body.put("createdAt", summary.createdAt().toString());
        body.put("updatedAt", summary.updatedAt().toString());
        body.put("versions", summary.versions().stream().map(version -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version", version.version());
            item.put("status", version.status().name());
            item.put("hash", version.hash());
            item.put("author", version.author());
            item.put("createdAt", version.createdAt().toString());
            item.put("removedAt", version.removedAt().map(Object::toString).orElse(null));
            return item;
        }).toList());
        return body;
    }

    static Map<String, Object> version(PolicyVersionDetails details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", details.name());
        body.put("version", details.version());
        body.put("status", details.status().name());
        body.put("hash", details.hash());
        body.put("author", details.author());
        body.put("createdAt", details.createdAt().toString());
        body.put("definition", PolicyDocument.write(details.definition()));
        return body;
    }

    static Map<String, Object> published(PublishedVersion published) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", published.name());
        body.put("version", published.version());
        body.put("hash", published.hash());
        body.put("created", published.created());
        return body;
    }

    static Map<String, Object> stableMove(String name, StableMove move) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("from", move.from().toString());
        body.put("to", move.to().toString());
        body.put("kind", move.kind().name());
        body.put("discardedCandidate", move.discardedCandidate().map(Object::toString).orElse(null));
        return body;
    }

    static Map<String, Object> audit(AuditRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("occurredAt", record.occurredAt().toString());
        body.put("policy", record.policy());
        body.put("actor", record.actor());
        body.put("action", record.action());
        body.put("details", record.details());
        return body;
    }

    static Map<String, Object> page(Page<Map<String, Object>> page) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items());
        body.put("pageNumber", page.pageNumber());
        body.put("pageSize", page.pageSize());
        body.put("totalItems", page.totalItems());
        return body;
    }
}
