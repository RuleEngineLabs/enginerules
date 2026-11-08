package br.com.lucaslima.enginerules.domain.rollout.canary;

import java.util.List;

public sealed interface CanaryVerdict {

    record KeepObserving() implements CanaryVerdict {
    }

    record Promote() implements CanaryVerdict {
    }

    record Abort(List<String> reasons) implements CanaryVerdict {
        public Abort {
            reasons = List.copyOf(reasons);
        }
    }

    record Inconclusive(String reason) implements CanaryVerdict {
    }
}
