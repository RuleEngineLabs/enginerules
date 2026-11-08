package br.com.lucaslima.enginerules.application.catalog.port.out;

import java.util.Optional;

import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;

public interface LoadPolicyPort {

    Optional<Policy> loadPolicy(PolicyName name, ReadConsistency consistency);
}
