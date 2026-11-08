package br.com.lucaslima.enginerules.application.catalog.port.in;

import java.util.Optional;

public record AuditFilter(Optional<String> policy, Optional<String> actor, Optional<String> action) {

    public static AuditFilter of(String policy, String actor, String action) {
        return new AuditFilter(nonBlank(policy), nonBlank(actor), nonBlank(action));
    }

    public boolean matches(AuditRecord record) {
        return policy.map(record.policy()::equals).orElse(true)
                && actor.map(record.actor()::equals).orElse(true)
                && action.map(record.action()::equals).orElse(true);
    }

    private static Optional<String> nonBlank(String value) {
        return Optional.ofNullable(value).filter(text -> !text.isBlank());
    }
}
