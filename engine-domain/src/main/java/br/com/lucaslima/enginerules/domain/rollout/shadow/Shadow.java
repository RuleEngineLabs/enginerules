package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;
import br.com.lucaslima.enginerules.domain.rollout.TrafficSelector;

public final class Shadow {

    public static final Duration DEFAULT_DURATION = Duration.ofHours(24);
    public static final Duration MAX_DURATION = Duration.ofDays(7);

    private final String id;
    private final PolicyName policy;
    private final SemanticVersion target;
    private final int percentage;
    private final List<VirtualRoute> virtualRoutes;
    private final List<String> ignoredPaths;
    private final String startedBy;
    private final Instant startedAt;
    private final Instant expiresAt;
    private final long revision;
    private ShadowStatus status;
    private Instant endedAt;

    private Shadow(ShadowSnapshot snapshot) {
        this.id = Objects.requireNonNull(snapshot.id(), "id");
        this.policy = Objects.requireNonNull(snapshot.policy(), "policy");
        this.target = Objects.requireNonNull(snapshot.target(), "target");
        this.percentage = snapshot.percentage();
        this.virtualRoutes = snapshot.virtualRoutes();
        this.ignoredPaths = snapshot.ignoredPaths();
        this.status = Objects.requireNonNull(snapshot.status(), "status");
        this.startedBy = Objects.requireNonNull(snapshot.startedBy(), "startedBy");
        this.startedAt = Objects.requireNonNull(snapshot.startedAt(), "startedAt");
        this.expiresAt = Objects.requireNonNull(snapshot.expiresAt(), "expiresAt");
        this.endedAt = snapshot.endedAt();
        this.revision = snapshot.revision();
    }

    public static Shadow start(String id, PolicyName policy, SemanticVersion target, int percentage, Duration duration,
                               List<VirtualRoute> virtualRoutes, List<String> ignoredPaths, String actor, Instant now) {
        if (percentage < 1 || percentage > 100) {
            throw new RolloutException(RolloutErrorCode.INVALID_SHADOW, "percentage must be between 1 and 100");
        }
        Duration effective = duration == null ? DEFAULT_DURATION : duration;
        if (effective.isNegative() || effective.isZero() || effective.compareTo(MAX_DURATION) > 0) {
            throw new RolloutException(RolloutErrorCode.INVALID_SHADOW, "duration must be positive and at most " + MAX_DURATION);
        }
        ignoredPaths.forEach(IgnoredPaths::validate);
        return new Shadow(new ShadowSnapshot(id, policy, target, percentage, virtualRoutes, ignoredPaths,
                ShadowStatus.ACTIVE, actor, now, now.plus(effective), null, 0));
    }

    public static Shadow restore(ShadowSnapshot snapshot) {
        return new Shadow(snapshot);
    }

    public ShadowStatus statusAt(Instant now) {
        if ((status == ShadowStatus.ACTIVE || status == ShadowStatus.PAUSED) && !now.isBefore(expiresAt)) {
            return ShadowStatus.EXPIRED;
        }
        return status;
    }

    public boolean isLiveAt(Instant now) {
        ShadowStatus current = statusAt(now);
        return current == ShadowStatus.ACTIVE || current == ShadowStatus.PAUSED;
    }

    public boolean copies(Optional<String> consumerId, Instant now, RandomGenerator random) {
        return statusAt(now) == ShadowStatus.ACTIVE && TrafficSelector.selects(consumerId, id, percentage, random);
    }

    public void pause(Instant now) {
        requireStatus(ShadowStatus.ACTIVE, now);
        status = ShadowStatus.PAUSED;
    }

    public void resume(Instant now) {
        requireStatus(ShadowStatus.PAUSED, now);
        status = ShadowStatus.ACTIVE;
    }

    public void end(Instant now) {
        if (!isLiveAt(now)) {
            throw new RolloutException(RolloutErrorCode.SHADOW_NOT_LIVE, "shadow " + id + " is " + statusAt(now));
        }
        status = ShadowStatus.ENDED;
        endedAt = now;
    }

    public ShadowSnapshot snapshot() {
        return new ShadowSnapshot(id, policy, target, percentage, virtualRoutes, ignoredPaths, status, startedBy, startedAt,
                expiresAt, endedAt, revision);
    }

    public String id() {
        return id;
    }

    public PolicyName policy() {
        return policy;
    }

    public SemanticVersion target() {
        return target;
    }

    public List<VirtualRoute> virtualRoutes() {
        return virtualRoutes;
    }

    public List<String> ignoredPaths() {
        return ignoredPaths;
    }

    public long revision() {
        return revision;
    }

    private void requireStatus(ShadowStatus expected, Instant now) {
        ShadowStatus current = statusAt(now);
        if (current != expected) {
            throw new RolloutException(RolloutErrorCode.SHADOW_NOT_LIVE, "shadow " + id + " is " + current
                    + ", expected " + expected);
        }
    }
}
