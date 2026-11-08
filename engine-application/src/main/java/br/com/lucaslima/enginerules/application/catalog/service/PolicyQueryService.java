package br.com.lucaslima.enginerules.application.catalog.service;

import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyQueries;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicySummary;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyVersionDetails;
import br.com.lucaslima.enginerules.application.catalog.port.out.ListPoliciesPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.application.shared.Page;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.catalog.VersionEntry;

public final class PolicyQueryService implements PolicyQueries {

    private final CatalogLookup catalog;
    private final ListPoliciesPort listPolicies;

    public PolicyQueryService(CatalogLookup catalog, ListPoliciesPort listPolicies) {
        this.catalog = catalog;
        this.listPolicies = listPolicies;
    }

    @Override
    public PolicySummary findPolicy(String name) {
        return PolicySummary.of(catalog.requirePolicy(PolicyName.of(name), ReadConsistency.DIRECT));
    }

    @Override
    public PolicyVersionDetails findVersion(String name, String version) {
        PolicyName policyName = PolicyName.of(name);
        Policy policy = catalog.requirePolicy(policyName, ReadConsistency.DIRECT);
        SemanticVersion requested = "stable".equalsIgnoreCase(version) ? policy.stable() : SemanticVersion.parse(version);
        VersionEntry entry = policy.entry(requested);
        PolicyVersion content = catalog.requireVersion(policyName, requested);
        return new PolicyVersionDetails(policyName.value(), requested.toString(), policy.statusOf(requested),
                entry.hash().value(), entry.author(), entry.createdAt(), content.definition());
    }

    @Override
    public Page<PolicySummary> listPolicies(PolicyFilter filter, PageRequest page) {
        return listPolicies.listPolicies(filter, page).map(PolicySummary::of);
    }
}
