package br.com.lucaslima.enginerules.application.rollout.port.in;

import java.time.Duration;
import java.util.Optional;

import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;

public interface CanaryUseCases {

    CanaryView start(StartCommand command);

    CanaryView cancel(String policy, Actor actor, String reason);

    CanaryView end(String policy, Actor actor, String reason);

    CanaryView extend(String policy, Actor actor, Duration by);

    record StartCommand(String policy, String candidate, int weight, Duration duration, Optional<CanaryCriteria> criteria,
                        Optional<String> shadowRef, Actor actor) {
    }
}
