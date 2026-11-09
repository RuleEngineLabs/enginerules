package br.com.lucaslima.enginerules.adapter.cache;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import br.com.lucaslima.enginerules.application.rollout.port.out.ShadowQueuePort;

public final class BoundedShadowQueue implements ShadowQueuePort, AutoCloseable {

    private final ThreadPoolExecutor workers;
    private final AtomicLong dropped = new AtomicLong();

    public BoundedShadowQueue(int concurrency, int capacity) {
        this.workers = new ThreadPoolExecutor(concurrency, concurrency, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofVirtual().name("shadow-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
    }

    @Override
    public boolean offer(Runnable shadowExecution) {
        try {
            workers.execute(shadowExecution);
            return true;
        } catch (RejectedExecutionException full) {
            dropped.incrementAndGet();
            return false;
        }
    }

    public int queued() {
        return workers.getQueue().size();
    }

    public long dropped() {
        return dropped.get();
    }

    @Override
    public void close() {
        workers.shutdown();
    }
}
