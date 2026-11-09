package br.com.lucaslima.enginerules.domain.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class Policy {

    private final PolicyName name;
    private final String ownerGroup;
    private final Set<String> approvers;
    private final Set<String> allowedConsumers;
    private final TreeMap<SemanticVersion, VersionEntry> versions = new TreeMap<>();
    private final Instant createdAt;
    private final long revision;
    private final List<PolicyEvent> pendingEvents = new ArrayList<>();
    private SemanticVersion stable;
    private SemanticVersion pendingCandidate;
    private long stableGeneration;
    private Instant updatedAt;

    private Policy(PolicySnapshot snapshot) {
        this.name = Objects.requireNonNull(snapshot.name(), "name");
        this.ownerGroup = Objects.requireNonNull(snapshot.ownerGroup(), "ownerGroup");
        this.approvers = snapshot.approvers();
        this.allowedConsumers = snapshot.allowedConsumers();
        this.stable = Objects.requireNonNull(snapshot.stable(), "stable");
        this.pendingCandidate = snapshot.pendingCandidate();
        this.stableGeneration = snapshot.stableGeneration();
        this.createdAt = snapshot.createdAt();
        this.updatedAt = snapshot.updatedAt();
        this.revision = snapshot.revision();
        snapshot.versions().forEach(entry -> versions.put(entry.version(), entry));
        requireInvariants();
    }

    public static Policy create(PolicyName name, String ownerGroup, Set<String> allowedConsumers, ContentHash hash,
                                Actor actor, Instant now) {
        if (ownerGroup == null || ownerGroup.isBlank() || !actor.belongsTo(ownerGroup)) {
            throw new CatalogException(CatalogErrorCode.NOT_OWNER,
                    "the creator must belong to the owner group '" + ownerGroup + "'");
        }
        VersionEntry first = new VersionEntry(SemanticVersion.FIRST, hash, actor.id(), now, null);
        Policy policy = new Policy(new PolicySnapshot(name, ownerGroup, Set.of(), allowedConsumers,
                SemanticVersion.FIRST, null, 1, List.of(first), now, now, 0));
        policy.pendingEvents.add(new PolicyEvent.PolicyCreated(name, ownerGroup, SemanticVersion.FIRST, actor.id(), now));
        return policy;
    }

    public static Policy restore(PolicySnapshot snapshot) {
        return new Policy(snapshot);
    }

    public Publication publish(Actor actor, SemanticVersion basedOn, ContentHash hash, VersionBump bump, Instant now) {
        requireOwnerMember(actor);
        if (basedOn == null) {
            throw new CatalogException(CatalogErrorCode.BASED_ON_REQUIRED, "basedOn is required for an existing policy");
        }
        if (!basedOn.equals(stable)) {
            throw new CatalogException(CatalogErrorCode.BASED_ON_NOT_STABLE,
                    "basedOn " + basedOn + " is not the STABLE version " + stable);
        }
        if (versions.get(stable).hash().equals(hash)) {
            return new Publication(stable, false);
        }
        if (pendingCandidate != null) {
            VersionEntry candidate = versions.get(pendingCandidate);
            if (candidate.hash().equals(hash) && candidate.author().equals(actor.id())) {
                return new Publication(pendingCandidate, false);
            }
            throw new CatalogException(CatalogErrorCode.PENDING_CANDIDATE_EXISTS,
                    "version " + pendingCandidate + " is already pending promotion");
        }
        VersionBump effectiveBump = bump == VersionBump.NONE ? VersionBump.PATCH : bump;
        SemanticVersion next = versions.lastKey().bump(effectiveBump);
        versions.put(next, new VersionEntry(next, hash, actor.id(), now, null));
        pendingCandidate = next;
        updatedAt = now;
        pendingEvents.add(new PolicyEvent.VersionPublished(name, next, basedOn, effectiveBump, actor.id(), now));
        return new Publication(next, true);
    }

    public StableMove moveStable(Actor actor, SemanticVersion target, Instant now) {
        requireOwnerOrOperator(actor);
        VersionEntry entry = existingEntry(target);
        if (entry.isRemoved()) {
            throw new CatalogException(CatalogErrorCode.VERSION_REMOVED, "version " + target + " was removed");
        }
        if (target.equals(stable)) {
            throw new CatalogException(CatalogErrorCode.ALREADY_STABLE, "version " + target + " is already STABLE");
        }
        SemanticVersion previous = stable;
        StableMove move;
        if (target.equals(pendingCandidate)) {
            pendingCandidate = null;
            move = new StableMove(previous, target, StableMove.Kind.PROMOTION, Optional.empty());
        } else {
            Optional<SemanticVersion> discarded = Optional.ofNullable(pendingCandidate);
            discarded.ifPresent(version -> versions.put(version, versions.get(version).removed(now)));
            pendingCandidate = previous;
            move = new StableMove(previous, target, StableMove.Kind.ROLLBACK, discarded);
        }
        stable = target;
        stableGeneration++;
        updatedAt = now;
        pendingEvents.add(new PolicyEvent.StableVersionMoved(name, move.from(), move.to(), move.kind(),
                move.discardedCandidate(), actor.id(), now));
        return move;
    }

    public void removeVersion(Actor actor, SemanticVersion version, Instant now) {
        requireOwnerOrOperator(actor);
        VersionEntry entry = existingEntry(version);
        if (entry.isRemoved()) {
            throw new CatalogException(CatalogErrorCode.VERSION_REMOVED, "version " + version + " was already removed");
        }
        if (version.equals(stable)) {
            throw new CatalogException(CatalogErrorCode.VERSION_IS_STABLE, "the STABLE version cannot be removed");
        }
        versions.put(version, entry.removed(now));
        if (version.equals(pendingCandidate)) {
            pendingCandidate = null;
        }
        updatedAt = now;
        pendingEvents.add(new PolicyEvent.VersionRemoved(name, version, actor.id(), now));
    }

    public SemanticVersion resolveExecutable(VersionSelector selector, Optional<String> consumerId) {
        if (!allowedConsumers.isEmpty() && consumerId.filter(allowedConsumers::contains).isEmpty()) {
            throw new CatalogException(CatalogErrorCode.CONSUMER_NOT_ALLOWED,
                    "consumer is not allowed to execute policy " + name);
        }
        return switch (selector) {
            case VersionSelector.Stable ignored -> stable;
            case VersionSelector.Unresolvable unresolvable -> throw new CatalogException(
                    CatalogErrorCode.VERSION_NOT_FOUND, "version " + unresolvable.requested() + " does not exist");
            case VersionSelector.Exact exact -> requireExecutable(exact.version());
        };
    }

    public boolean isManageableBy(Actor actor) {
        return actor.belongsTo(ownerGroup) || actor.isOperator();
    }

    public boolean hasVersion(SemanticVersion version) {
        return versions.containsKey(version);
    }

    public VersionStatus statusOf(SemanticVersion version) {
        VersionEntry entry = existingEntry(version);
        if (entry.isRemoved()) {
            return VersionStatus.REMOVED;
        }
        if (version.equals(stable)) {
            return VersionStatus.STABLE;
        }
        if (version.equals(pendingCandidate)) {
            return VersionStatus.PENDING_CANDIDATE;
        }
        return VersionStatus.RETIRED;
    }

    public VersionEntry entry(SemanticVersion version) {
        return existingEntry(version);
    }

    public List<PolicyEvent> drainEvents() {
        List<PolicyEvent> drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    public PolicySnapshot snapshot() {
        return new PolicySnapshot(name, ownerGroup, approvers, allowedConsumers, stable, pendingCandidate,
                stableGeneration, List.copyOf(versions.values()), createdAt, updatedAt, revision);
    }

    public PolicyName name() {
        return name;
    }

    public String ownerGroup() {
        return ownerGroup;
    }

    public SemanticVersion stable() {
        return stable;
    }

    public Optional<SemanticVersion> pendingCandidate() {
        return Optional.ofNullable(pendingCandidate);
    }

    public long stableGeneration() {
        return stableGeneration;
    }

    public long revision() {
        return revision;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<VersionEntry> versions() {
        return List.copyOf(versions.values());
    }

    private SemanticVersion requireExecutable(SemanticVersion version) {
        VersionEntry entry = existingEntry(version);
        if (entry.isRemoved()) {
            throw new CatalogException(CatalogErrorCode.VERSION_REMOVED, "version " + version + " was removed");
        }
        if (version.equals(pendingCandidate)) {
            throw new CatalogException(CatalogErrorCode.PENDING_CANDIDATE_NOT_EXECUTABLE,
                    "version " + version + " is pending promotion and only runs through preview or canary");
        }
        return version;
    }

    private VersionEntry existingEntry(SemanticVersion version) {
        VersionEntry entry = versions.get(version);
        if (entry == null) {
            throw new CatalogException(CatalogErrorCode.VERSION_NOT_FOUND,
                    "policy " + name + " has no version " + version);
        }
        return entry;
    }

    private void requireOwnerMember(Actor actor) {
        if (!actor.belongsTo(ownerGroup)) {
            throw new CatalogException(CatalogErrorCode.NOT_OWNER,
                    actor.id() + " does not belong to the owner group of " + name);
        }
    }

    private void requireOwnerOrOperator(Actor actor) {
        if (!isManageableBy(actor)) {
            throw new CatalogException(CatalogErrorCode.NOT_OWNER,
                    actor.id() + " is neither owner of " + name + " nor operator");
        }
    }

    private void requireInvariants() {
        VersionEntry stableEntry = versions.get(stable);
        if (stableEntry == null || stableEntry.isRemoved()) {
            throw new IllegalStateException("policy " + name + " must have exactly one live STABLE version");
        }
        if (pendingCandidate != null && (!versions.containsKey(pendingCandidate) || pendingCandidate.equals(stable))) {
            throw new IllegalStateException("policy " + name + " has an inconsistent pending candidate");
        }
    }
}
