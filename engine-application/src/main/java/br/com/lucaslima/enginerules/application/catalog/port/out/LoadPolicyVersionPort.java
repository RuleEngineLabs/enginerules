package br.com.lucaslima.enginerules.application.catalog.port.out;

import java.util.Optional;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;

public interface LoadPolicyVersionPort {

    Optional<PolicyVersion> loadVersion(PolicyName name, SemanticVersion version);
}
