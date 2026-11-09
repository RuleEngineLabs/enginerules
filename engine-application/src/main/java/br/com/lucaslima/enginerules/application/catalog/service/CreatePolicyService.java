package br.com.lucaslima.enginerules.application.catalog.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishedVersion;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;

public final class CreatePolicyService implements CreatePolicyUseCase {

    private final LoadPolicyPort policies;
    private final SavePolicyPort savePolicy;
    private final AppendAuditPort audit;
    private final PolicyCompiler compiler;
    private final Clock clock;

    public CreatePolicyService(LoadPolicyPort policies, SavePolicyPort savePolicy, AppendAuditPort audit,
                               PolicyCompiler compiler, Clock clock) {
        this.policies = policies;
        this.savePolicy = savePolicy;
        this.audit = audit;
        this.compiler = compiler;
        this.clock = clock;
    }

    @Override
    public PublishedVersion create(Command command) {
        PolicyName name = PolicyName.of(command.name());
        compiler.compile(command.definition());
        if (policies.loadPolicy(name, ReadConsistency.DIRECT).isPresent()) {
            throw new CatalogException(CatalogErrorCode.POLICY_ALREADY_EXISTS, "policy " + name + " already exists");
        }
        PolicyVersion content = PolicyVersion.of(name, SemanticVersion.FIRST, command.definition());
        Policy policy = Policy.create(name, command.ownerGroup(), command.allowedConsumers(), content.hash(),
                command.actor(), clock.instant());
        savePolicy.savePolicy(policy, Optional.of(content));
        List<AuditRecord> records = policy.drainEvents().stream().map(AuditRecord::from).toList();
        audit.append(records);
        return new PublishedVersion(name.value(), SemanticVersion.FIRST.toString(), content.hash().value(), true);
    }
}
