package br.com.lucaslima.enginerules.application.rollout.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;

final class RolloutAudit {

    private final AppendAuditPort audit;

    RolloutAudit(AppendAuditPort audit) {
        this.audit = audit;
    }

    void canary(String action, Canary canary, String actor, Instant at, Map<String, String> extra) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("canaryId", canary.id());
        details.put("candidate", canary.candidate().toString());
        details.put("baseline", canary.baseline().toString());
        details.put("status", canary.status().name());
        details.putAll(extra);
        audit.append(List.of(new AuditRecord(at, canary.policy().value(), actor, action, details)));
    }

    void shadow(String action, Shadow shadow, String actor, Instant at) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("shadowId", shadow.id());
        details.put("target", shadow.target().toString());
        details.put("status", shadow.statusAt(at).name());
        audit.append(List.of(new AuditRecord(at, shadow.policy().value(), actor, action, details)));
    }
}
