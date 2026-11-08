package br.com.lucaslima.enginerules.application.catalog.port.in;

import br.com.lucaslima.enginerules.domain.catalog.Actor;

public interface RemoveVersionUseCase {

    void remove(Command command);

    record Command(String name, String version, Actor actor) {
    }
}
