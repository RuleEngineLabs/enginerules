package br.com.lucaslima.enginerules.application.rollout.port.in;

import java.util.List;

public interface EvaluateCanariesUseCase {

    List<Evaluation> evaluateOpenCanaries();

    record Evaluation(String policy, String canaryId, String verdict, List<String> reasons) {
        public Evaluation {
            reasons = List.copyOf(reasons);
        }
    }
}
