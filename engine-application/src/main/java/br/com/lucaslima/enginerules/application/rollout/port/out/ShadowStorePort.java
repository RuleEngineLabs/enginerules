package br.com.lucaslima.enginerules.application.rollout.port.out;

import java.util.List;
import java.util.Optional;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.rollout.shadow.Shadow;

public interface ShadowStorePort {

    Optional<Shadow> findShadow(PolicyName name, String id);

    List<Shadow> shadowHistory(PolicyName name);

    void save(Shadow shadow);
}
