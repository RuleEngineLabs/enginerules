package br.com.lucaslima.enginerules.application.catalog.service;

import java.time.Clock;
import java.util.Optional;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishedVersion;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.ChangeClassifier;
import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.Publication;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionBump;
import br.com.lucaslima.enginerules.domain.execution.PolicyCompiler;

public final class PublishVersionService implements PublishVersionUseCase {

    private final CatalogLookup catalog;
    private final SavePolicyPort savePolicy;
    private final AppendAuditPort audit;
    private final PolicyCompiler compiler;
    private final ChangeClassifier changeClassifier;
    private final Clock clock;

    public PublishVersionService(CatalogLookup catalog, SavePolicyPort savePolicy, AppendAuditPort audit,
                                 PolicyCompiler compiler, ChangeClassifier changeClassifier, Clock clock) {
        this.catalog = catalog;
        this.savePolicy = savePolicy;
        this.audit = audit;
        this.compiler = compiler;
        this.changeClassifier = changeClassifier;
        this.clock = clock;
    }

    @Override
    public PublishedVersion publish(Command command) {
        PolicyName name = PolicyName.of(command.name());
        if (command.basedOn() == null || command.basedOn().isBlank()) {
            throw new CatalogException(CatalogErrorCode.BASED_ON_REQUIRED, "basedOn is required for an existing policy");
        }
        compiler.compile(command.definition());
        Policy policy = catalog.requirePolicy(name, ReadConsistency.DIRECT);
        PolicyVersion stable = catalog.requireVersion(name, policy.stable());
        VersionBump bump = changeClassifier.classify(stable.definition(), command.definition());
        ContentHash hash = ContentHash.of(command.definition());
        Publication publication = policy.publish(command.actor(), SemanticVersion.parse(command.basedOn()), hash, bump,
                clock.instant());
        if (publication.created()) {
            savePolicy.savePolicy(policy, Optional.of(new PolicyVersion(name, publication.version(), command.definition(), hash)));
            audit.append(policy.drainEvents().stream().map(AuditRecord::from).toList());
        }
        return new PublishedVersion(name.value(), publication.version().toString(),
                policy.entry(publication.version()).hash().value(), publication.created());
    }
}
