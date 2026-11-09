package br.com.lucaslima.enginerules.application.catalog.service;

import java.time.Clock;
import java.util.Optional;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.MoveStableVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.RolloutGuardPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.StableMove;

public final class MoveStableVersionService implements MoveStableVersionUseCase {

    private final CatalogLookup catalog;
    private final SavePolicyPort savePolicy;
    private final AppendAuditPort audit;
    private final RolloutGuardPort rollouts;
    private final Clock clock;

    public MoveStableVersionService(CatalogLookup catalog, SavePolicyPort savePolicy, AppendAuditPort audit,
                                    RolloutGuardPort rollouts, Clock clock) {
        this.catalog = catalog;
        this.savePolicy = savePolicy;
        this.audit = audit;
        this.rollouts = rollouts;
        this.clock = clock;
    }

    @Override
    public StableMove moveStable(Command command) {
        Policy policy = catalog.requirePolicy(PolicyName.of(command.name()), ReadConsistency.DIRECT);
        if (rollouts.hasOpenCanary(policy.name())) {
            throw new CatalogException(CatalogErrorCode.CANARY_IN_PROGRESS,
                    "cancel the open canary of " + policy.name() + " or wait for it to finish before moving STABLE");
        }
        StableMove move = policy.moveStable(command.actor(), SemanticVersion.parse(command.version()), clock.instant());
        savePolicy.savePolicy(policy, Optional.empty());
        audit.append(policy.drainEvents().stream().map(AuditRecord::from).toList());
        return move;
    }
}
