package br.com.lucaslima.enginerules.application.catalog.port.in;

import java.util.Set;

import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;

public interface CreatePolicyUseCase {

    PublishedVersion create(Command command);

    record Command(String name, String ownerGroup, Set<String> allowedConsumers, PolicyDefinition definition, Actor actor) {
    }
}
