package br.com.lucaslima.enginerules.domain.rollout.canary;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;
import br.com.lucaslima.enginerules.domain.rollout.Route;
import br.com.lucaslima.enginerules.domain.rollout.TrafficSelector;

public final class Canary {

    public static final Duration DEFAULT_DURATION = Duration.ofHours(24);
    public static final Duration MAX_DURATION = Duration.ofHours(24);
    public static final Duration EXTENSION_BUDGET = Duration.ofHours(24);

    private final String id;
    private final PolicyName policy;
    private final SemanticVersion candidate;
    private final SemanticVersion baseline;
    private final int weight;
    private final Duration duration;
    private final CanaryCriteria criteria;
    private final String shadowRef;
    private final String startedBy;
    private final Instant startedAt;
    private final long revision;
    private Duration extension;
    private CanaryStatus status;
    private boolean awaitingExtension;
    private Instant closedAt;
    private String closingReason;

    private Canary(CanarySnapshot snapshot) {
        this.id = Objects.requireNonNull(snapshot.id(), "id");
        this.policy = Objects.requireNonNull(snapshot.policy(), "policy");
        this.candidate = Objects.requireNonNull(snapshot.candidate(), "candidate");
        this.baseline = Objects.requireNonNull(snapshot.baseline(), "baseline");
        this.weight = snapshot.weight();
        this.duration = Objects.requireNonNull(snapshot.duration(), "duration");
        this.extension = Objects.requireNonNull(snapshot.extension(), "extension");
        this.criteria = Objects.requireNonNull(snapshot.criteria(), "criteria");
        this.shadowRef = snapshot.shadowRef();
        this.status = Objects.requireNonNull(snapshot.status(), "status");
        this.awaitingExtension = snapshot.awaitingExtension();
        this.startedBy = Objects.requireNonNull(snapshot.startedBy(), "startedBy");
        this.startedAt = Objects.requireNonNull(snapshot.startedAt(), "startedAt");
        this.closedAt = snapshot.closedAt();
        this.closingReason = snapshot.closingReason();
        this.revision = snapshot.revision();
    }

    public static Canary start(String id, PolicyName policy, SemanticVersion candidate, SemanticVersion baseline, int weight,
                               Duration duration, CanaryCriteria criteria, Optional<String> shadowRef, String actor,
                               Instant now) {
        if (weight < 1 || weight > 99) {
            throw new RolloutException(RolloutErrorCode.INVALID_CANARY, "weight must be between 1 and 99");
        }
        Duration effectiveDuration = duration == null ? DEFAULT_DURATION : duration;
        if (effectiveDuration.isNegative() || effectiveDuration.isZero() || effectiveDuration.compareTo(MAX_DURATION) > 0) {
            throw new RolloutException(RolloutErrorCode.INVALID_CANARY, "duration must be positive and at most " + MAX_DURATION);
        }
        if (candidate.equals(baseline)) {
            throw new RolloutException(RolloutErrorCode.INVALID_CANARY, "candidate must differ from the STABLE version");
        }
        return new Canary(new CanarySnapshot(id, policy, candidate, baseline, weight, effectiveDuration, Duration.ZERO,
                criteria == null ? CanaryCriteria.GLOBAL_DEFAULT : criteria, shadowRef.orElse(null), CanaryStatus.ACTIVE,
                false, actor, now, null, null, 0));
    }

    public static Canary restore(CanarySnapshot snapshot) {
        return new Canary(snapshot);
    }

    public Route route(Optional<String> consumerId, RandomGenerator random) {
        if (!status.isOpen()) {
            return Route.STABLE;
        }
        return TrafficSelector.selects(consumerId, id, weight, random) ? Route.CANARY : Route.STABLE;
    }

    public void extend(Duration by) {
        requireStatus(CanaryStatus.ACTIVE);
        if (!awaitingExtension) {
            throw new RolloutException(RolloutErrorCode.CANARY_NOT_INCONCLUSIVE,
                    "only an inconclusive canary can be extended");
        }
        if (by == null || by.isNegative() || by.isZero() || extension.plus(by).compareTo(EXTENSION_BUDGET) > 0) {
            throw new RolloutException(RolloutErrorCode.EXTENSION_BUDGET_EXCEEDED,
                    "extensions add up to at most " + EXTENSION_BUDGET + "; already used " + extension);
        }
        extension = extension.plus(by);
        awaitingExtension = false;
    }

    public void markInconclusive() {
        requireStatus(CanaryStatus.ACTIVE);
        awaitingExtension = true;
    }

    public void beginConclusion() {
        requireStatus(CanaryStatus.ACTIVE);
        status = CanaryStatus.CONCLUDING;
    }

    public void completePromotion(Instant now) {
        requireStatus(CanaryStatus.CONCLUDING);
        close(CanaryStatus.PROMOTED, "criteria passed", now);
    }

    public void cancel(String reason, Instant now) {
        if (!status.isOpen()) {
            throw new RolloutException(RolloutErrorCode.CANARY_NOT_ACTIVE, "canary " + id + " is already " + status);
        }
        close(CanaryStatus.CANCELLED, reason, now);
    }

    public void end(String reason, Instant now) {
        requireStatus(CanaryStatus.ACTIVE);
        close(CanaryStatus.ENDED, reason, now);
    }

    public Instant decisionDeadline() {
        return startedAt.plus(duration).plus(extension);
    }

    public Instant absoluteDeadline() {
        return startedAt.plus(duration).plus(EXTENSION_BUDGET);
    }

    public CanarySnapshot snapshot() {
        return new CanarySnapshot(id, policy, candidate, baseline, weight, duration, extension, criteria, shadowRef, status,
                awaitingExtension, startedBy, startedAt, closedAt, closingReason, revision);
    }

    public String id() {
        return id;
    }

    public PolicyName policy() {
        return policy;
    }

    public SemanticVersion candidate() {
        return candidate;
    }

    public SemanticVersion baseline() {
        return baseline;
    }

    public CanaryCriteria criteria() {
        return criteria;
    }

    public Optional<String> shadowRef() {
        return Optional.ofNullable(shadowRef);
    }

    public CanaryStatus status() {
        return status;
    }

    public boolean awaitingExtension() {
        return awaitingExtension;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long revision() {
        return revision;
    }

    private void close(CanaryStatus finalStatus, String reason, Instant now) {
        status = finalStatus;
        closedAt = now;
        closingReason = reason;
        awaitingExtension = false;
    }

    private void requireStatus(CanaryStatus expected) {
        if (status != expected) {
            throw new RolloutException(RolloutErrorCode.CANARY_NOT_ACTIVE,
                    "canary " + id + " is " + status + ", expected " + expected);
        }
    }
}
