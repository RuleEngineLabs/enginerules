package br.com.lucaslima.enginerules.adapter.web.common;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.lucaslima.enginerules.application.shared.InvalidRequestException;
import br.com.lucaslima.enginerules.domain.catalog.Actor;

public final class ActorHeaders {

    public static final String ACTOR_ID = "X-Actor-Id";
    public static final String ACTOR_GROUPS = "X-Actor-Groups";

    private ActorHeaders() {
    }

    public static Actor resolve(String actorId, String groups) {
        if (actorId == null || actorId.isBlank()) {
            throw new InvalidRequestException(ACTOR_ID + " header is required");
        }
        Set<String> parsedGroups = groups == null ? Set.of() : Arrays.stream(groups.split(","))
                .map(String::trim)
                .filter(group -> !group.isEmpty())
                .collect(Collectors.toSet());
        return new Actor(actorId.trim(), parsedGroups);
    }
}
