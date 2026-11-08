package br.com.lucaslima.enginerules.domain.rollout;

public final class RolloutException extends RuntimeException {

    private final RolloutErrorCode code;

    public RolloutException(RolloutErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public RolloutErrorCode code() {
        return code;
    }
}
