package br.com.lucaslima.enginerules.application.catalog.service;

import java.time.Clock;
import java.util.Optional;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.RemoveVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.RolloutGuardPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;

public final class RemoveVersionService implements RemoveVersionUseCase {

    private final CatalogLookup catalog;
    private final SavePolicyPort savePolicy;
    private final AppendAuditPort audit;
    private final RolloutGuardPort rollouts;
    private final Clock clock;

    public RemoveVersionService(CatalogLookup catalog, SavePolicyPort savePolicy, AppendAuditPort audit,
                                RolloutGuardPort rollouts, Clock clock) {
        this.catalog = catalog;
        this.savePolicy = savePolicy;
        this.audit = audit;
        this.rollouts = rollouts;
        this.clock = clock;
    }

    @Override
    public void remove(Command command) {
        Policy policy = catalog.requirePolicy(PolicyName.of(command.name()), ReadConsistency.DIRECT);
        SemanticVersion version = SemanticVersion.parse(command.version());
        if (rollouts.isVersionInUse(policy.name(), version)) {
            throw new CatalogException(CatalogErrorCode.VERSION_IN_USE,
                    "version " + version + " is referenced by an open canary or a live shadow");
        }
        policy.removeVersion(command.actor(), version, clock.instant());
        savePolicy.savePolicy(policy, Optional.empty());
        audit.append(policy.drainEvents().stream().map(AuditRecord::from).toList());
    }
}
