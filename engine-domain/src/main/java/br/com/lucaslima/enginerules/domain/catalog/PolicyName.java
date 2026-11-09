package br.com.lucaslima.enginerules.domain.catalog;

import java.util.Set;
import java.util.regex.Pattern;

public record PolicyName(String value) implements Comparable<PolicyName> {

    private static final Pattern CAMEL_CASE = Pattern.compile("[A-Z][A-Za-z0-9]{0,31}");
    private static final Set<String> RESERVED = Set.of("Preview", "Health", "Metrics", "Admin");

    public PolicyName {
        if (value == null || !CAMEL_CASE.matcher(value).matches()) {
            throw new CatalogException(CatalogErrorCode.INVALID_POLICY_NAME,
                    "policy name must be CamelCase with up to 32 characters: " + value);
        }
        if (RESERVED.contains(value)) {
            throw new CatalogException(CatalogErrorCode.INVALID_POLICY_NAME, "policy name '" + value + "' is reserved");
        }
    }

    public static PolicyName of(String value) {
        return new PolicyName(value);
    }

    @Override
    public int compareTo(PolicyName other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
