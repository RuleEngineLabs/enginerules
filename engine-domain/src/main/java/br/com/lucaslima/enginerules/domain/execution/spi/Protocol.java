package br.com.lucaslima.enginerules.domain.execution.spi;

import java.util.Arrays;
import java.util.Optional;

public enum Protocol {
    REST("rest"),
    GRPC("grpc");

    private final String contractName;

    Protocol(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static Optional<Protocol> fromContractName(String name) {
        if (name == null) {
            return Optional.of(REST);
        }
        return Arrays.stream(values()).filter(protocol -> protocol.contractName.equals(name)).findFirst();
    }
}
