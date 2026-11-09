package br.com.lucaslima.enginerules.adapter.web.admin;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.lucaslima.enginerules.adapter.web.common.ActorHeaders;
import br.com.lucaslima.enginerules.application.catalog.port.in.CreatePolicyUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.MoveStableVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyFilter;
import br.com.lucaslima.enginerules.application.catalog.port.in.PolicyQueries;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishVersionUseCase;
import br.com.lucaslima.enginerules.application.catalog.port.in.PublishedVersion;
import br.com.lucaslima.enginerules.application.catalog.port.in.RemoveVersionUseCase;
import br.com.lucaslima.enginerules.application.shared.PageRequest;
import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.StableMove;

@RestController
public class PolicyAdminController {

    private final CreatePolicyUseCase createPolicy;
    private final PublishVersionUseCase publishVersion;
    private final MoveStableVersionUseCase moveStableVersion;
    private final RemoveVersionUseCase removeVersion;
    private final PolicyQueries queries;

    public PolicyAdminController(CreatePolicyUseCase createPolicy, PublishVersionUseCase publishVersion,
                                 MoveStableVersionUseCase moveStableVersion, RemoveVersionUseCase removeVersion,
                                 PolicyQueries queries) {
        this.createPolicy = createPolicy;
        this.publishVersion = publishVersion;
        this.moveStableVersion = moveStableVersion;
        this.removeVersion = removeVersion;
        this.queries = queries;
    }

    @GetMapping("/policies")
    public Map<String, Object> list(
            @RequestParam(name = "namePrefix", required = false) String namePrefix,
            @RequestParam(name = "owner", required = false) String owner,
            @RequestParam(name = "pendingCandidate", required = false) Boolean pendingCandidate,
            @RequestParam(name = "pageNumber", required = false) Integer pageNumber,
            @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        return AdminResponses.page(queries.listPolicies(PolicyFilter.of(namePrefix, owner, pendingCandidate),
                PageRequest.of(pageNumber, pageSize)).map(AdminResponses::policy));
    }

    @PostMapping("/policies")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        Actor actor = ActorHeaders.resolve(actorId, actorGroups);
        PublishedVersion published = createPolicy.create(new CreatePolicyUseCase.Command(
                RequestBodies.requiredText(body, "name"), RequestBodies.requiredText(body, "ownerGroup"),
                RequestBodies.textSet(body, "allowedConsumers"), RequestBodies.definition(body), actor));
        return ResponseEntity.status(201).body(AdminResponses.published(published));
    }

    @GetMapping("/policies/{name}")
    public Map<String, Object> find(@PathVariable("name") String name) {
        return AdminResponses.policy(queries.findPolicy(name));
    }

    @PostMapping("/policies/{name}/versions")
    public ResponseEntity<Map<String, Object>> publish(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        Actor actor = ActorHeaders.resolve(actorId, actorGroups);
        PublishedVersion published = publishVersion.publish(new PublishVersionUseCase.Command(
                name, RequestBodies.requiredText(body, "basedOn"), RequestBodies.definition(body), actor));
        return ResponseEntity.status(published.created() ? 201 : 200).body(AdminResponses.published(published));
    }

    @GetMapping("/policies/{name}/versions/{version}")
    public Map<String, Object> findVersion(@PathVariable("name") String name, @PathVariable("version") String version) {
        return AdminResponses.version(queries.findVersion(name, version));
    }

    @PutMapping("/policies/{name}/stable-version")
    public Map<String, Object> moveStable(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        Actor actor = ActorHeaders.resolve(actorId, actorGroups);
        StableMove move = moveStableVersion.moveStable(new MoveStableVersionUseCase.Command(
                name, RequestBodies.requiredText(body, "version"), actor));
        return AdminResponses.stableMove(name, move);
    }

    @DeleteMapping("/policies/{name}/versions/{version}")
    public ResponseEntity<Void> remove(
            @PathVariable("name") String name,
            @PathVariable("version") String version,
            @RequestHeader(name = ActorHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(name = ActorHeaders.ACTOR_GROUPS, required = false) String actorGroups) {
        removeVersion.remove(new RemoveVersionUseCase.Command(name, version, ActorHeaders.resolve(actorId, actorGroups)));
        return ResponseEntity.noContent().build();
    }
}
