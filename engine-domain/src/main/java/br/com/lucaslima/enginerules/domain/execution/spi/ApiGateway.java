package br.com.lucaslima.enginerules.domain.execution.spi;

public interface ApiGateway {

    ApiResponse call(ApiRequest request);
}
