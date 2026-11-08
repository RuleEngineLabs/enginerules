package br.com.lucaslima.enginerules.application.rollout.port.out;

import java.util.Optional;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;

public interface LoadOpenCanaryPort {

    Optional<Canary> findOpen(PolicyName name, ReadConsistency consistency);
}
