package br.com.lucaslima.enginerules.application.catalog.port.out;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;

public interface RolloutGuardPort {

    boolean hasOpenCanary(PolicyName name);

    boolean isVersionInUse(PolicyName name, SemanticVersion version);
}
