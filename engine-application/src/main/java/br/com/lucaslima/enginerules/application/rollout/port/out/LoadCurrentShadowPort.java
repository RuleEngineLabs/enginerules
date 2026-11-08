package br.com.lucaslima.enginerules.application.rollout.port.out;

import java.util.Optional;

import br.com.lucaslima.enginerules.application.catalog.port.out.ReadConsistency;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;

public interface LoadCurrentShadowPort {

    Optional<Shadow> findCurrent(PolicyName name, ReadConsistency consistency);
}
