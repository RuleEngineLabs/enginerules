package br.com.lucaslima.enginerules.application.catalog.port.in;

public record PublishedVersion(String name, String version, String hash, boolean created) {
}
