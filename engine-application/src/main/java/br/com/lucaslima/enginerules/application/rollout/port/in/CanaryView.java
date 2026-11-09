package br.com.lucaslima.enginerules.application.rollout.port.in;

import br.com.lucaslima.enginerules.domain.rollout.canary.Canary;
import br.com.lucaslima.enginerules.domain.rollout.canary.CanarySnapshot;

public record CanaryView(CanarySnapshot snapshot, String decisionDeadline, String absoluteDeadline) {

    public static CanaryView of(Canary canary) {
        return new CanaryView(canary.snapshot(), canary.decisionDeadline().toString(), canary.absoluteDeadline().toString());
    }
}
