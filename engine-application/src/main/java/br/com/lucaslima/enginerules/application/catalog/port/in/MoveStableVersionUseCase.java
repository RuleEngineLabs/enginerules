package br.com.lucaslima.enginerules.application.catalog.port.in;

import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.StableMove;

public interface MoveStableVersionUseCase {

    StableMove moveStable(Command command);

    record Command(String name, String version, Actor actor) {
    }
}
