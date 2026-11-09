package br.com.lucaslima.enginerules.domain.rollout;

public enum Route {
    STABLE("stable"),
    CANARY("canary"),
    PINNED("pinned"),
    SHADOW("shadow");

    private final String label;

    Route(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean servesRealTraffic() {
        return this == STABLE || this == CANARY;
    }
}
