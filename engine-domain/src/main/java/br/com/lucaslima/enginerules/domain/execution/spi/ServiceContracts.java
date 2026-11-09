package br.com.lucaslima.enginerules.domain.execution.spi;

import java.util.Optional;
import java.util.Set;

public interface ServiceContracts {

    Optional<String> verifyGrpcCall(String connection, String service, String rpc, Set<String> messageFields);

    static ServiceContracts unverified() {
        return (connection, service, rpc, messageFields) -> Optional.empty();
    }
}
