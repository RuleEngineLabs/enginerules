package br.com.lucaslima.enginerules.application.rollout.port.in;

import java.util.List;

public interface RolloutQueries {

    List<CanaryView> canaries(String policy);

    List<ShadowView> shadows(String policy);

    ShadowReport shadowReport(String policy, String shadowId);
}
