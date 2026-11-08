package br.com.lucaslima.enginerules.domain.catalog;

public final class CatalogException extends RuntimeException {

    private final CatalogErrorCode code;

    public CatalogException(CatalogErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public CatalogErrorCode code() {
        return code;
    }
}
