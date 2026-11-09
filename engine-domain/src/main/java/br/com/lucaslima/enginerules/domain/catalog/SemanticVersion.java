package br.com.lucaslima.enginerules.domain.catalog;

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {

    public static final SemanticVersion FIRST = new SemanticVersion(1, 0, 0);
    private static final Pattern FORMAT = Pattern.compile("v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");
    private static final Comparator<SemanticVersion> ORDER = Comparator.comparingInt(SemanticVersion::major)
            .thenComparingInt(SemanticVersion::minor)
            .thenComparingInt(SemanticVersion::patch);

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new CatalogException(CatalogErrorCode.INVALID_VERSION, "version components cannot be negative");
        }
    }

    public static SemanticVersion parse(String text) {
        Matcher matcher = text == null ? null : FORMAT.matcher(text.trim());
        if (matcher == null || !matcher.matches()) {
            throw new CatalogException(CatalogErrorCode.INVALID_VERSION, "not a semantic version: " + text);
        }
        return new SemanticVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    public static boolean isWellFormed(String text) {
        return text != null && FORMAT.matcher(text.trim()).matches();
    }

    public SemanticVersion bump(VersionBump bump) {
        return switch (bump) {
            case MAJOR -> new SemanticVersion(major + 1, 0, 0);
            case MINOR -> new SemanticVersion(major, minor + 1, 0);
            case PATCH -> new SemanticVersion(major, minor, patch + 1);
            case NONE -> this;
        };
    }

    @Override
    public int compareTo(SemanticVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
