package br.com.lucaslima.enginerules.application.catalog.service;

import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.LoadPolicyVersionPort;
import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.domain.catalog.CatalogErrorCode;
import br.com.lucaslima.enginerules.domain.catalog.CatalogException;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;

public final class CatalogLookup {

    private final LoadPolicyPort policies;
    private final LoadPolicyVersionPort versions;

    public CatalogLookup(LoadPolicyPort policies, LoadPolicyVersionPort versions) {
        this.policies = policies;
        this.versions = versions;
    }

    public Policy requirePolicy(PolicyName name, ReadConsistency consistency) {
        return policies.loadPolicy(name, consistency)
                .orElseThrow(() -> new CatalogException(CatalogErrorCode.POLICY_NOT_FOUND, "policy " + name + " does not exist"));
    }

    public PolicyVersion requireVersion(PolicyName name, SemanticVersion version) {
        return versions.loadVersion(name, version)
                .orElseThrow(() -> new CatalogException(CatalogErrorCode.VERSION_NOT_FOUND,
                        "content of " + name + "@" + version + " is missing"));
    }
}
