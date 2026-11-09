package br.com.lucaslima.enginerules.domain.execution.spi;

public final class ExpressionSyntaxException extends RuntimeException {

    public ExpressionSyntaxException(String message) {
        super(message);
    }
}
