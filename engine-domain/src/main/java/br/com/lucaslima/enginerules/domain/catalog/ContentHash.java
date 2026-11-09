package br.com.lucaslima.enginerules.domain.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import br.com.lucaslima.enginerules.domain.definition.CanonicalForm;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;

public record ContentHash(String value) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public ContentHash {
        if (value == null || !SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("content hash must be a lowercase SHA-256 hex digest");
        }
    }

    public static ContentHash of(PolicyDefinition definition) {
        return new ContentHash(sha256(CanonicalForm.of(definition)));
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 must be available in every JVM", missing);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
