package br.com.lucaslima.enginerules.application.catalog.port.in;

import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;

public interface PublishVersionUseCase {

    PublishedVersion publish(Command command);

    record Command(String name, String basedOn, PolicyDefinition definition, Actor actor) {
    }
}
