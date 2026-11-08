package br.com.lucaslima.enginerules.application.shared;

public final class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
