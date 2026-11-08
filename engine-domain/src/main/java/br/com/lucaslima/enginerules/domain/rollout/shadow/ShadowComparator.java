package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.util.ArrayList;
import java.util.List;

import br.com.lucaslima.enginerules.domain.execution.ExecutionOutcome;

public final class ShadowComparator {

    private final List<String> globalIgnoredPaths;

    public ShadowComparator(List<String> globalIgnoredPaths) {
        globalIgnoredPaths.forEach(IgnoredPaths::validate);
        this.globalIgnoredPaths = List.copyOf(globalIgnoredPaths);
    }

    public ComparisonResult compare(ExecutionOutcome real, ExecutionOutcome shadow, boolean virtualIoMissed,
                                    List<String> policyIgnoredPaths) {
        if (virtualIoMissed) {
            return ComparisonResult.NOT_COMPARABLE;
        }
        return switch (real) {
            case ExecutionOutcome.Responded realResponse -> compareResponded(realResponse, shadow, policyIgnoredPaths);
            case ExecutionOutcome.Failed realFailure -> switch (shadow) {
                case ExecutionOutcome.Failed shadowFailure -> realFailure.error() == shadowFailure.error()
                        ? ComparisonResult.EQUAL : ComparisonResult.DIVERGENT_OUTCOME;
                default -> ComparisonResult.REAL_ERROR_ONLY;
            };
            case ExecutionOutcome.PreviewBlocked ignored -> ComparisonResult.NOT_COMPARABLE;
        };
    }

    private ComparisonResult compareResponded(ExecutionOutcome.Responded real, ExecutionOutcome shadow,
                                              List<String> policyIgnoredPaths) {
        return switch (shadow) {
            case ExecutionOutcome.Failed ignored -> ComparisonResult.SHADOW_ERROR_ONLY;
            case ExecutionOutcome.PreviewBlocked ignored -> ComparisonResult.NOT_COMPARABLE;
            case ExecutionOutcome.Responded shadowResponse -> {
                if (!real.state().equals(shadowResponse.state()) || real.status() != shadowResponse.status()) {
                    yield ComparisonResult.DIVERGENT_OUTCOME;
                }
                List<String> ignored = new ArrayList<>(globalIgnoredPaths);
                ignored.addAll(policyIgnoredPaths);
                yield IgnoredPaths.without(real.data(), ignored).equals(IgnoredPaths.without(shadowResponse.data(), ignored))
                        ? ComparisonResult.EQUAL : ComparisonResult.DIVERGENT_DATA;
            }
        };
    }
}
