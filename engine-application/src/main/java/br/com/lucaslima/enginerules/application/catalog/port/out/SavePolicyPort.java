package br.com.lucaslima.enginerules.application.catalog.port.out;

import java.util.Optional;

import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;

public interface SavePolicyPort {

    void savePolicy(Policy policy, Optional<PolicyVersion> newVersion);
}
