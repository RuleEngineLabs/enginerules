package br.com.lucaslima.enginerules.domain.definition;

import java.util.List;

public final class InvalidPolicyDocumentException extends RuntimeException {

    private final List<String> problems;

    public InvalidPolicyDocumentException(List<String> problems) {
        super("invalid policy document: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
