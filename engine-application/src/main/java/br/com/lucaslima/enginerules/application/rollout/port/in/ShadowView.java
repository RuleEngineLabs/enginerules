package br.com.lucaslima.enginerules.application.rollout.port.in;

import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowSnapshot;
import br.com.lucaslima.enginerules.domain.rollout.shadow.ShadowStatus;

public record ShadowView(ShadowSnapshot snapshot, ShadowStatus effectiveStatus) {
}
