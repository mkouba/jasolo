package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MavenSourcesResolverTest {

    Path base;
    MavenSourcesResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createTempDirectory("jasolo-m2-test");
        Fixtures.createM2(base);
        resolver = newResolver(TestConfigs.of(null, base.resolve("m2")));
    }

    private MavenSourcesResolver newResolver(JasoloConfig config) {
        // fetching disabled by default in TestConfigs.of()
        return new MavenSourcesResolver(config, new MavenSourcesFetcher(config));
    }

    @AfterEach
    void tearDown() {
        Fixtures.delete(base);
    }

    @Test
    void locateAndRead() {
        Optional<LocatedSources> located = resolver.locate(Gav.parse(Fixtures.M2_ONLY_GAV));
        assertTrue(located.isPresent());
        LocatedSources sources = located.get();
        assertTrue(sources.origin().contains("lib-extra-1.0.0-sources.jar"));

        Optional<String> content = sources.read("org.example.libextra.Extra");
        assertTrue(content.isPresent());
        assertTrue(content.get().contains("class Extra"));

        assertTrue(sources.read("org.example.libextra.Nope").isEmpty());
    }

    @Test
    void listFqcns() {
        Optional<LocatedSources> located = resolver.locate(Gav.parse(Fixtures.M2_ONLY_GAV));
        assertTrue(located.isPresent());
        // module-info.java is filtered out, src/main/java rooting is stripped, list is sorted
        assertEquals(List.of("org.example.legacy.Legacy", "org.example.libextra.Extra"),
                located.get().listFqcns());
    }

    @Test
    void locateMissing() {
        assertTrue(resolver.locate(Gav.parse(Fixtures.MISSING_GAV)).isEmpty());
        assertTrue(resolver.locate(Gav.parse("org.example:lib-extra:9.9.9")).isEmpty());
    }

    @Test
    void locateMissingFetchesOnDemand() throws IOException {
        // the sources jar of the missing artifact is "downloaded" by a fake
        // Maven command - the resolver locates it on the next check
        Path jar = base.resolve("fetched-sources.jar");
        Fixtures.writeJar(jar, Map.of("org/example/missing/Missing.java",
                "package org.example.missing;\npublic class Missing { /* fetched */ }\n"));
        Path script = Fixtures.writeScript(base.resolve("fake-mvn"), """
                #!/bin/sh
                repo=""
                for a in "$@"; do
                  case "$a" in
                    -Dmaven.repo.local=*) repo="${a#-Dmaven.repo.local=}";;
                  esac
                done
                mkdir -p "$repo/org/example/missing/1.0.0"
                cp %s "$repo/org/example/missing/1.0.0/missing-1.0.0-sources.jar"
                """.formatted(jar));
        var config = TestConfigs.of(null, base.resolve("m2"),
                "show", 16, 256, List.of(), List.of(), true, List.of(script.toString()));
        MavenSourcesResolver fetchResolver = newResolver(config);

        Optional<LocatedSources> located = fetchResolver.locate(Gav.parse(Fixtures.MISSING_GAV));
        assertTrue(located.isPresent());
        assertTrue(located.get().origin().contains("missing-1.0.0-sources.jar"));
        assertTrue(located.get().read("org.example.missing.Missing").orElseThrow().contains("fetched"));
    }

    @Test
    void locateMissingFetchFails() throws IOException {
        // Maven cannot find the sources jar - the resolver declines and the
        // fetcher remembers the failure
        Path script = Fixtures.writeScript(base.resolve("fake-mvn-fail"), """
                #!/bin/sh
                echo "[ERROR] Could not find artifact org.example:missing:jar:sources:1.0.0 in central" >&2
                exit 1
                """);
        var config = TestConfigs.of(null, base.resolve("m2"),
                "show", 16, 256, List.of(), List.of(), true, List.of(script.toString()));
        MavenSourcesFetcher fetcher = new MavenSourcesFetcher(config);
        MavenSourcesResolver fetchResolver = new MavenSourcesResolver(config, fetcher);

        assertTrue(fetchResolver.locate(Gav.parse(Fixtures.MISSING_GAV)).isEmpty());
        assertTrue(fetcher.failureFor(Gav.parse(Fixtures.MISSING_GAV)).orElseThrow()
                .contains("no artifact with coordinates"));
    }

    @Test
    void validateLocalRepo() {
        // the fixture local repository exists - the construction in setUp()
        // already proves that the validation passes

        // a configured path that does not exist fails the construction
        Path missingRepo = base.resolve("nonexistent-m2");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> newResolver(TestConfigs.of(null, missingRepo)));
        assertTrue(e.getMessage().contains("Maven repository does not exist"));
    }

    @Test
    void cachedContentAndStaleness() throws IOException {
        LocatedSources sources = resolver.locate(Gav.parse(Fixtures.M2_ONLY_GAV)).orElseThrow();
        assertTrue(sources.read("org.example.libextra.Extra").orElseThrow().contains("class Extra {}"));
        assertEquals(List.of("org.example.legacy.Legacy", "org.example.libextra.Extra"), sources.listFqcns());

        // the jar is rebuilt on disk with a single changed entry
        rewriteLibExtraJar(Map.of("org/example/libextra/Extra.java",
                "package org.example.libextra;\npublic class Extra { /* rewritten-extra */ }\n"));

        // the cached instance still serves the old content and listing
        assertTrue(sources.read("org.example.libextra.Extra").orElseThrow().contains("class Extra {}"));
        assertEquals(2, sources.listFqcns().size());

        // re-locating detects the change (modification time/size) and serves fresh content
        LocatedSources fresh = resolver.locate(Gav.parse(Fixtures.M2_ONLY_GAV)).orElseThrow();
        assertTrue(fresh.read("org.example.libextra.Extra").orElseThrow().contains("rewritten-extra"));
        assertEquals(List.of("org.example.libextra.Extra"), fresh.listFqcns());
    }

    @Test
    void cacheDisabled() throws IOException {
        resolver = newResolver(TestConfigs.of(null, base.resolve("m2"), 0, 0));
        LocatedSources sources = resolver.locate(Gav.parse(Fixtures.M2_ONLY_GAV)).orElseThrow();
        assertTrue(sources.read("org.example.libextra.Extra").orElseThrow().contains("class Extra {}"));

        rewriteLibExtraJar(Map.of("org/example/libextra/Extra.java",
                "package org.example.libextra;\npublic class Extra { /* rewritten-extra */ }\n"));

        // no caching - the change is visible immediately, even on the same instance
        assertTrue(sources.read("org.example.libextra.Extra").orElseThrow().contains("rewritten-extra"));
    }

    @Test
    void cacheExclusion() throws IOException {
        // the artifact is excluded from caching via configuration
        resolver = newResolver(TestConfigs.of(null, base.resolve("m2"),
                List.of(Fixtures.M2_ONLY_GA)));

        LocatedSources sources = resolver.locate(Gav.parse(Fixtures.M2_ONLY_GAV)).orElseThrow();
        assertTrue(sources.read("org.example.libextra.Extra").orElseThrow().contains("class Extra {}"));
        // the jar is never cached
        assertFalse(resolver.cache.contains(resolver.sourcesJar(Gav.parse(Fixtures.M2_ONLY_GAV))));

        rewriteLibExtraJar(Map.of("org/example/libextra/Extra.java",
                "package org.example.libextra;\npublic class Extra { /* rewritten-extra */ }\n"));

        // nothing is cached - even the same instance serves fresh content
        assertTrue(sources.read("org.example.libextra.Extra").orElseThrow().contains("rewritten-extra"));
    }

    @Test
    void contentEviction() throws IOException {
        resolver = newResolver(TestConfigs.of(null, base.resolve("m2"), 16, 1));
        LocatedSources sources = resolver.locate(Gav.parse(Fixtures.M2_ONLY_GAV)).orElseThrow();
        assertTrue(sources.read("org.example.libextra.Extra").orElseThrow().contains("class Extra {}"));
        // reading another class evicts the first one (max-files = 1)
        assertTrue(sources.read("org.example.legacy.Legacy").orElseThrow().contains("class Legacy"));

        // both entries are rebuilt on disk with different content
        rewriteLibExtraJar(Map.of(
                "org/example/libextra/Extra.java",
                "package org.example.libextra;\npublic class Extra { /* rewritten-extra */ }\n",
                "src/main/java/org/example/legacy/Legacy.java",
                "package org.example.legacy;\npublic class Legacy { /* rewritten-legacy */ }\n"));

        // Legacy is still cached - the old content is served
        assertFalse(sources.read("org.example.legacy.Legacy").orElseThrow().contains("rewritten-legacy"));
        // Extra was evicted - the content is re-read from the rebuilt jar
        assertTrue(sources.read("org.example.libextra.Extra").orElseThrow().contains("rewritten-extra"));
    }

    @Test
    void jarEviction() {
        resolver = newResolver(TestConfigs.of(null, base.resolve("m2"), 1, 256));
        Path libCoreJar = resolver.sourcesJar(Gav.parse(Fixtures.M2_FALLBACK_GAV));
        Path libExtraJar = resolver.sourcesJar(Gav.parse(Fixtures.M2_ONLY_GAV));

        resolver.locate(Gav.parse(Fixtures.M2_FALLBACK_GAV));
        assertTrue(resolver.cache.contains(libCoreJar));

        // locating another jar evicts the least recently used one (max-jars = 1)
        resolver.locate(Gav.parse(Fixtures.M2_ONLY_GAV));
        assertFalse(resolver.cache.contains(libCoreJar));
        assertTrue(resolver.cache.contains(libExtraJar));
    }

    private void rewriteLibExtraJar(Map<String, String> entries) throws IOException {
        Fixtures.writeSourcesJar(base.resolve("m2"), "org.example", "lib-extra", "1.0.0", entries);
    }
}
