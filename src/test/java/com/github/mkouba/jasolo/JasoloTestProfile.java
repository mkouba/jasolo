package com.github.mkouba.jasolo;

import java.nio.file.Path;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Boots the application against fixture git roots and a fixture local Maven
 * repository. The fixtures are created when this class is initialized, i.e.
 * before Quarkus reads the config overrides.
 */
public class JasoloTestProfile implements QuarkusTestProfile {

    private static final Path BASE = Fixtures.create();

    public static final String GIT_ROOT = BASE.resolve("roots").toString();
    public static final String M2_REPO = BASE.resolve("m2").toString();

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "jasolo.git.roots", GIT_ROOT,
                "jasolo.maven.local-repo", M2_REPO);
    }
}
