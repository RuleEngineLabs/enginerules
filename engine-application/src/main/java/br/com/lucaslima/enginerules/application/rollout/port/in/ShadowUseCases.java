package br.com.lucaslima.enginerules.application.rollout.port.in;

import java.time.Duration;
import java.util.List;

import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualRoute;

public interface ShadowUseCases {

    ShadowView start(StartCommand command);

    ShadowView pause(String policy, String shadowId, Actor actor);

    ShadowView resume(String policy, String shadowId, Actor actor);

    ShadowView end(String policy, String shadowId, Actor actor);

    record StartCommand(String policy, String target, int percentage, Duration duration, List<VirtualRoute> virtualRoutes,
                        List<String> ignoredPaths, Actor actor) {
    }
}
