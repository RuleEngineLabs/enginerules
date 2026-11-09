package br.com.lucaslima.enginerules.application.rollout.port.out;

public interface ShadowQueuePort {

    boolean offer(Runnable shadowExecution);
}
