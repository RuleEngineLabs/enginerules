package br.com.lucaslima.enginerules.application.rollout.port.out;

import java.util.List;
import java.util.Optional;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;

public interface CanaryStorePort {

    Optional<Canary> findCanary(PolicyName name, String id);

    List<Canary> canaryHistory(PolicyName name);

    List<Canary> listOpen();

    void save(Canary canary);
}
