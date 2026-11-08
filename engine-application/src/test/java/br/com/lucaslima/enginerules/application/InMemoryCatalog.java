package br.com.lucaslima.enginerules.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import br.com.lucaslima.enginerules.application.catalog.port.in.AuditFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.AuditRecord;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.out.AppendAuditPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListAuditsPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListPoliciesPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyVersionPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.catalog.port.out.SavePolicyPort;
import br.com.lucaslima.enginerules.application.execution.port.out.CompiledPolicyCachePort;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicySnapshot;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.execution.CompiledPolicy;

class InMemoryCatalog implements LoadPolicyPort, LoadPolicyVersionPort, SavePolicyPort, ListPoliciesPort,
        AppendAuditPort, ListAuditsPort, CompiledPolicyCachePort {

    private final Map<PolicyName, PolicySnapshot> policies = new ConcurrentHashMap<>();
    private final Map<String, PolicyVersion> versions = new ConcurrentHashMap<>();
    private final Map<String, CompiledPolicy> compiled = new ConcurrentHashMap<>();
    private final List<AuditRecord> audits = new ArrayList<>();

    @Override
    public Optional<Policy> loadPolicy(PolicyName name, ReadConsistency consistency) {
        return Optional.ofNullable(policies.get(name)).map(Policy::restore);
    }

    @Override
    public Optional<PolicyVersion> loadVersion(PolicyName name, SemanticVersion version) {
        return Optional.ofNullable(versions.get(name + "@" + version));
    }

    @Override
    public synchronized void savePolicy(Policy policy, Optional<PolicyVersion> newVersion) {
        newVersion.ifPresent(version -> versions.putIfAbsent(version.name() + "@" + version.version(), version));
        PolicySnapshot snapshot = policy.snapshot();
        policies.put(policy.name(), new PolicySnapshot(snapshot.name(), snapshot.ownerGroup(), snapshot.approvers(),
                snapshot.allowedConsumers(), snapshot.stable(), snapshot.pendingCandidate(), snapshot.stableGeneration(),
                snapshot.versions(), snapshot.createdAt(), snapshot.updatedAt(), snapshot.revision() + 1));
    }

    @Override
    public Page<Policy> listPolicies(PolicyFilter filter, PageRequest page) {
        List<Policy> all = policies.values().stream().map(Policy::restore)
                .sorted((left, right) -> left.name().compareTo(right.name())).toList();
        return Page.slice(all, page);
    }

    @Override
    public synchronized void append(List<AuditRecord> records) {
        audits.addAll(records);
    }

    @Override
    public synchronized Page<AuditRecord> listAudits(AuditFilter filter, PageRequest page) {
        return Page.slice(audits.stream().filter(filter::matches).toList(), page);
    }

    @Override
    public CompiledPolicy getOrCompile(PolicyName name, SemanticVersion version, Supplier<CompiledPolicy> compilation) {
        return compiled.computeIfAbsent(name + "@" + version, ignored -> compilation.get());
    }

    synchronized List<AuditRecord> audits() {
        return List.copyOf(audits);
    }
}
