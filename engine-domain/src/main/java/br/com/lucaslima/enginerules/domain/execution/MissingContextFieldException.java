package br.com.lucaslima.enginerules.domain.execution;

final class MissingContextFieldException extends RuntimeException {

    MissingContextFieldException(String path) {
        super("required context field '" + path + "' is not present");
    }
}
