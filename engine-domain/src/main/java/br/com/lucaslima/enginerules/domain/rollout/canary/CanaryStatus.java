package br.com.lucaslima.enginerules.domain.rollout.canary;

public enum CanaryStatus {
    ACTIVE,
    CONCLUDING,
    PROMOTED,
    CANCELLED,
    ENDED;

    public boolean isOpen() {
        return this == ACTIVE || this == CONCLUDING;
    }
}
