package br.com.lucaslima.enginerules.adapter.persistence.aws;

public record AwsTables(String policies, String policyIndex, String audit, String canaries, String shadows) {

    public static final String ACTIVE_INDEX = "active-by-status";

    public static AwsTables withPrefix(String prefix) {
        return new AwsTables(prefix + "policies", prefix + "policy-index", prefix + "audit", prefix + "canaries",
                prefix + "shadows");
    }
}
