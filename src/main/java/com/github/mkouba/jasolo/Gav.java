package com.github.mkouba.jasolo;

import java.util.Objects;

/**
 * Maven coordinates: {@code groupId:artifactId:version}.
 */
public record Gav(String groupId, String artifactId, String version) {

    private static final String SEPARATOR = ":";

    public Gav {
        Objects.requireNonNull(groupId);
        Objects.requireNonNull(artifactId);
        Objects.requireNonNull(version);
    }

    /**
     * @param value e.g. {@code io.quarkus:quarkus-core:3.27.5.1}
     * @return the parsed GAV
     * @throws IllegalArgumentException if the value is not a valid {@code g:a:v} string
     */
    public static Gav parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("GAV must not be null");
        }
        String[] parts = value.trim().split(SEPARATOR);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Invalid GAV '" + value + "': expected format groupId:artifactId:version, e.g. io.quarkus:quarkus-core:3.27.5.1");
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
            if (parts[i].isEmpty()) {
                throw new IllegalArgumentException("Invalid GAV '" + value + "': no segment may be empty");
            }
        }
        return new Gav(parts[0], parts[1], parts[2]);
    }

    public boolean isSnapshot() {
        return version.endsWith("-SNAPSHOT");
    }

    /**
     * @return the ga key used by the resolver chain, i.e. {@code groupId:artifactId}
     */
    public String ga() {
        return groupId + SEPARATOR + artifactId;
    }

    @Override
    public String toString() {
        return groupId + SEPARATOR + artifactId + SEPARATOR + version;
    }
}
