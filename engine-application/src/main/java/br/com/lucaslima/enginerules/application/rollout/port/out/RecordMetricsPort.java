package br.com.lucaslima.enginerules.application.rollout.port.out;

public interface RecordMetricsPort {

    void recordExecution(ExecutionSample sample);

    void recordShadow(ShadowSample sample);
}
