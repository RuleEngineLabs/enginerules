package br.com.lucaslima.enginerules.domain.execution;

import java.util.Objects;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiGateway;
import br.com.lucaslima.enginerules.domain.execution.spi.ResultCache;

public record IoBinding(ApiGateway apiGateway, ResultCache resultCache) {

    public IoBinding {
        Objects.requireNonNull(apiGateway, "apiGateway");
        Objects.requireNonNull(resultCache, "resultCache");
    }
}
