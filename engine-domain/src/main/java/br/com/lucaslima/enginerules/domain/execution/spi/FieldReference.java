package br.com.lucaslima.enginerules.domain.execution.spi;

import java.util.Objects;

public record FieldReference(String root, String field) {

    public FieldReference {
        Objects.requireNonNull(root, "root");
    }

    public static FieldReference rootOnly(String root) {
        return new FieldReference(root, null);
    }

    public String path() {
        return field == null ? root : root + "." + field;
    }
}
