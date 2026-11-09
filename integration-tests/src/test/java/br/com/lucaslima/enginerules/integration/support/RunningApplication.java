package br.com.lucaslima.enginerules.integration.support;

import java.net.URI;

public record RunningApplication(URI baseUri, Runnable stopper) implements AutoCloseable {

    @Override
    public void close() {
        stopper.run();
    }
}
