package br.com.lucaslima.enginerules.app.engine;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import br.com.lucaslima.enginerules.adapter.persistence.filesystem.FileSystemMetrics;

@Configuration(proxyBeanMethods = false)
class MetricsFlushScheduler {

    private final FileSystemMetrics metrics;

    MetricsFlushScheduler(FileSystemMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${enginerules.metrics.flush-interval:1s}")
    void flush() {
        metrics.flush();
    }
}
