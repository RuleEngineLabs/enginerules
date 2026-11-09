package br.com.lucaslima.enginerules.adapter.web.admin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import br.com.lucaslima.enginerules.adapter.web.common.ActorHeaders;
import br.com.lucaslima.enginerules.application.rollout.port.in.CanaryUseCases;
import br.com.lucaslima.enginerules.application.rollout.port.in.EvaluateCanariesUseCase;
import br.com.lucaslima.enginerules.application.rollout.port.in.RolloutQueries;
import br.com.lucaslima.enginerules.application.rollout.port.in.ShadowUseCases;
import br.com.lucaslima.enginerules.application.shared.InvalidRequestException;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanaryCriteria;
import br.com.lucaslima.enginerules.domain.rollout.shadow.VirtualRoute;

@RestController
public class RolloutController {

    private final CanaryUseCases canaries;
    private final ShadowUseCases shadows;
    private final RolloutQueries queries;
    private final EvaluateCanariesUseCase evaluations;

    public RolloutController(CanaryUseCases canaries, ShadowUseCases shadows, RolloutQueries queries,
                             EvaluateCanariesUseCase evaluations) {
        this.canaries = canaries;
        this.shadows = shadows;
        this.queries = queries;
        this.evaluations = evaluations;
    }

    @PostMapping("/policies/{name}/canaries")
    public ResponseEntity<Map<String, Object>> startCanary(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        Actor actor = ActorHeaders.resolve(actorId, actorGroups);
        CanaryUseCases.StartCommand command = new CanaryUseCases.StartCommand(name,
                RequestBodies.requiredText(body, "candidate"), integer(body, "weight", null),
                minutes(body, "durationMinutes"), criteria(body), Optional.ofNullable((String) body.get("shadowRef")), actor);
        return ResponseEntity.status(201).body(RolloutResponses.canary(canaries.start(command)));
    }

    @GetMapping("/policies/{name}/canaries")
    public List<Map<String, Object>> listCanaries(@PathVariable("name") String name) {
        return queries.canaries(name).stream().map(RolloutResponses::canary).toList();
    }

    @PatchMapping("/policies/{name}/canaries/current")
    public Map<String, Object> changeCanary(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        Actor actor = ActorHeaders.resolve(actorId, actorGroups);
        String reason = (String) body.get("reason");
        if (body.containsKey("extendMinutes")) {
            return RolloutResponses.canary(canaries.extend(name, actor, minutes(body, "extendMinutes")));
        }
        return switch (String.valueOf(body.get("status"))) {
            case "ENDED" -> RolloutResponses.canary(canaries.end(name, actor, reason));
            case "CANCELLED" -> RolloutResponses.canary(canaries.cancel(name, actor, reason));
            default -> throw new InvalidRequestException("send extendMinutes, or status ENDED or CANCELLED");
        };
    }

    @DeleteMapping("/policies/{name}/canaries/current")
    public Map<String, Object> cancelCanary(
            @PathVariable("name") String name,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        return RolloutResponses.canary(canaries.cancel(name, ActorHeaders.resolve(actorId, actorGroups), null));
    }

    @PostMapping("/canary-evaluations")
    public List<Map<String, Object>> evaluateCanaries() {
        return evaluations.evaluateOpenCanaries().stream().map(RolloutResponses::evaluation).toList();
    }

    @PostMapping("/policies/{name}/shadows")
    public ResponseEntity<Map<String, Object>> startShadow(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        Actor actor = ActorHeaders.resolve(actorId, actorGroups);
        ShadowUseCases.StartCommand command = new ShadowUseCases.StartCommand(name,
                RequestBodies.requiredText(body, "target"), integer(body, "percentage", null),
                minutes(body, "durationMinutes"), virtualRoutes(body), List.copyOf(RequestBodies.textSet(body, "ignoredPaths")),
                actor);
        return ResponseEntity.status(201).body(RolloutResponses.shadow(shadows.start(command)));
    }

    @GetMapping("/policies/{name}/shadows")
    public List<Map<String, Object>> listShadows(@PathVariable("name") String name) {
        return queries.shadows(name).stream().map(RolloutResponses::shadow).toList();
    }

    @PatchMapping("/policies/{name}/shadows/{id}")
    public Map<String, Object> changeShadow(
            @PathVariable("name") String name,
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        Actor actor = ActorHeaders.resolve(actorId, actorGroups);
        return switch (RequestBodies.requiredText(body, "status")) {
            case "PAUSED" -> RolloutResponses.shadow(shadows.pause(name, id, actor));
            case "ACTIVE" -> RolloutResponses.shadow(shadows.resume(name, id, actor));
            case "ENDED" -> RolloutResponses.shadow(shadows.end(name, id, actor));
            default -> throw new InvalidRequestException("status must be PAUSED, ACTIVE or ENDED");
        };
    }

    @GetMapping("/policies/{name}/shadows/{id}/report")
    public Map<String, Object> shadowReport(@PathVariable("name") String name, @PathVariable("id") String id) {
        return RolloutResponses.report(queries.shadowReport(name, id));
    }

    private static Optional<CanaryCriteria> criteria(Map<String, Object> body) {
        if (!(body.get("criteria") instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        CanaryCriteria defaults = CanaryCriteria.GLOBAL_DEFAULT;
        return Optional.of(new CanaryCriteria(
                decimal(raw.get("errorMarginPercentagePoints"), defaults.errorMarginPercentagePoints()),
                decimal(raw.get("maxLatencyRatio"), defaults.maxLatencyRatio()),
                (long) decimal(raw.get("minSamples"), defaults.minSamples()),
                decimal(raw.get("maxInputIncompatibilityRate"), defaults.maxInputIncompatibilityRate())));
    }

    private static List<VirtualRoute> virtualRoutes(Map<String, Object> body) {
        List<VirtualRoute> routes = new ArrayList<>();
        if (body.get("virtualRoutes") instanceof List<?> raw) {
            for (Object item : raw) {
                if (!(item instanceof Map<?, ?> route)) {
                    throw new InvalidRequestException("virtualRoutes items must be objects");
                }
                routes.add(new VirtualRoute((String) route.get("connection"), (String) route.get("method"),
                        (String) route.get("pathTemplate"), (int) decimal(route.get("status"), 200), route.get("body")));
            }
        }
        return routes;
    }

    private static int integer(Map<String, Object> body, String field, Integer fallback) {
        Object value = body.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (fallback != null) {
            return fallback;
        }
        throw new InvalidRequestException("'" + field + "' is required and must be a number");
    }

    private static Duration minutes(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof Number number ? Duration.ofMinutes(number.longValue()) : null;
    }

    private static double decimal(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
