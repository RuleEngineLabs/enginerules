package br.com.lucaslima.enginerules.domain.definition;

import java.util.Arrays;
import java.util.Optional;

public enum StateKind {
    EXECUTION("execution"),
    API_CALL("apiCall"),
    DB_QUERY("dbQuery"),
    PARALLEL("parallel"),
    RESPONSE("response");

    private final String contractName;

    StateKind(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static Optional<StateKind> fromContractName(String name) {
        return Arrays.stream(values()).filter(kind -> kind.contractName.equals(name)).findFirst();
    }
}
