package br.com.lucaslima.enginerules.app.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import br.com.lucaslima.enginerules.application.rollout.port.in.EvaluateCanariesUseCase;

@Configuration(proxyBeanMethods = false)
class CanaryEvaluationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CanaryEvaluationScheduler.class);

    private final EvaluateCanariesUseCase evaluations;

    CanaryEvaluationScheduler(EvaluateCanariesUseCase evaluations) {
        this.evaluations = evaluations;
    }

    @Scheduled(fixedDelayString = "${enginerules.canary.evaluation-interval:30s}")
    void evaluate() {
        evaluations.evaluateOpenCanaries().stream()
                .filter(evaluation -> !"observing".equals(evaluation.verdict()))
                .forEach(evaluation -> LOG.info("canary {} of {}: {} {}", evaluation.canaryId(), evaluation.policy(),
                        evaluation.verdict(), evaluation.reasons()));
    }
}
