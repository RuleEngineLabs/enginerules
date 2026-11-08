package br.com.lucaslima.enginerules.application.rollout.port.in;

import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStats;

public record ShadowReport(ShadowView shadow, ShadowStats stats) {
}
