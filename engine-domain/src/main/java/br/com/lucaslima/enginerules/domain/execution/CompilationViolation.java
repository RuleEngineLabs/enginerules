package br.com.lucaslima.enginerules.domain.execution;

public record CompilationViolation(String location, String message) {

    @Override
    public String toString() {
        return location + ": " + message;
    }
}
