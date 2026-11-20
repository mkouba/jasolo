package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SourceLocatorTest {

    Path base;
    SourceLocator locator;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createTempDirectory("jasolo-chain-test");
        Fixtures.createGitRoot(base);
        Fixtures.createM2(base);
        locator = newLocator(TestConfigs.of(base.resolve("roots"), base.resolve("m2")));
    }

    private SourceLocator newLocator(JasoloConfig config) {
        MavenSourcesFetcher fetcher = new MavenSourcesFetcher(config);
        return new SourceLocator(
                List.of(
                        new GitRepoResolver(config, new GitRepoIndex(config), List.of(new ShowGitAccessStrategy())),
                        new MavenSourcesResolver(config, fetcher)),
                fetcher);
    }

    @AfterEach
    void tearDown() {
        Fixtures.delete(base);
    }

    @Test
    void gitResolverWins() {
        // both resolvers can serve 2.0.0 - the git resolver has the higher priority
        LocatedSources located = locator.locate(Gav.parse(Fixtures.GIT_TAG_GAV));
        assertTrue(located.origin().contains("git repo"));
        assertTrue(located.read("org.example.libcore.Core").orElseThrow().contains("marker: tag-2.0.0"));
    }

    @Test
    void fallsThroughToMavenSources() {
        // 3.1.0 has no git ref - the m2 sources jar must be used
        LocatedSources located = locator.locate(Gav.parse(Fixtures.M2_FALLBACK_GAV));
        assertTrue(located.origin().contains("maven sources jar"));
        assertTrue(located.read("org.example.libcore.Core").orElseThrow().contains("marker: m2-jar"));
    }

    @Test
    void exactMatchPreferredOverApproximate() {
        // the git branch 2.0 matches 2.0.7 approximately, the m2 sources jar exactly
        LocatedSources located = locator.locate(Gav.parse(Fixtures.BRANCH_AND_M2_GAV));
        assertTrue(located.origin().contains("maven sources jar"), located.origin());
        assertTrue(located.exact());
        assertTrue(located.read("org.example.libcore.Core").orElseThrow().contains("marker: m2-jar-2.0.7"));
    }

    @Test
    void approximateMatchUsedWithoutExactMatch() {
        // no 2.0.5 tag and no 2.0.5 sources jar - the git branch 2.0 is used
        LocatedSources located = locator.locate(Gav.parse(Fixtures.GIT_BRANCH_GAV));
        assertTrue(located.origin().contains("git repo"), located.origin());
        assertFalse(located.exact());
        assertEquals(1, located.warnings().size());
    }

    @Test
    void mavenOnlyArtifact() {
        LocatedSources located = locator.locate(Gav.parse(Fixtures.M2_ONLY_GAV));
        assertTrue(located.origin().contains("maven sources jar"));
    }

    @Test
    void nothingResolves() {
        SourceNotFoundException e = assertThrows(SourceNotFoundException.class,
                () -> locator.locate(Gav.parse(Fixtures.MISSING_GAV)));
        assertEquals(List.of("git-repo", "m2-sources-jar"), e.tried());
        assertTrue(e.getMessage().contains(Fixtures.MISSING_GAV));
        // fetching is disabled - the error is actionable: it carries the exact command
        assertTrue(e.getMessage().contains(
                "mvn -B dependency:get -Dartifact=" + Fixtures.MISSING_GAV + ":jar:sources"));
        assertTrue(e.getMessage().contains("jasolo.maven.fetch.enabled=true"));
        assertNull(e.fetchFailure());
    }

    @Test
    void resolverOrder() {
        assertEquals(List.of("git-repo", "m2-sources-jar"), locator.resolverNames());
    }

    @Test
    void fetchOnDemand() throws IOException {
        // a fake Maven command that "downloads" the sources jar of the missing
        // artifact into the local repository
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

        var config = TestConfigs.of(base.resolve("roots"), base.resolve("m2"),
                "show", 16, 256, List.of(), List.of(), true, List.of(script.toString()));
        var fetchLocator = newLocator(config);

        LocatedSources located = fetchLocator.locate(Gav.parse(Fixtures.MISSING_GAV));
        assertTrue(located.exact());
        assertTrue(located.origin().contains("missing-1.0.0-sources.jar"), located.origin());
        assertTrue(located.read("org.example.missing.Missing").orElseThrow().contains("fetched"));
    }

    @Test
    void fetchFailsSourcesNotPublished() throws IOException {
        // the binary jar of the missing artifact exists locally - Maven only
        // cannot find the :sources classifier
        Fixtures.writeBinaryJar(base.resolve("m2"), "org.example", "missing", "1.0.0");
        Path script = Fixtures.writeScript(base.resolve("fake-mvn-fail"), """
                #!/bin/sh
                echo "[ERROR] Could not find artifact org.example:missing:jar:sources:1.0.0 in central" >&2
                exit 1
                """);

        var config = TestConfigs.of(base.resolve("roots"), base.resolve("m2"),
                "show", 16, 256, List.of(), List.of(), true, List.of(script.toString()));
        var fetcher = new MavenSourcesFetcher(config);
        var fetchLocator = new SourceLocator(
                List.of(
                        new GitRepoResolver(config, new GitRepoIndex(config), List.of(new ShowGitAccessStrategy())),
                        new MavenSourcesResolver(config, fetcher)),
                fetcher);

        SourceNotFoundException e = assertThrows(SourceNotFoundException.class,
                () -> fetchLocator.locate(Gav.parse(Fixtures.MISSING_GAV)));
        // distinct, actionable status: the artifact exists but no sources jar is published
        assertTrue(e.getMessage().contains("no sources jar is published"), e.getMessage());
        assertTrue(e.fetchFailure().contains("decompiling"));

        // the failed GAV is remembered - a repeated attempt does not re-run Maven
        MavenSourcesFetcher.FetchResult second = fetcher.fetch(Gav.parse(Fixtures.MISSING_GAV));
        assertTrue(second.detail().contains("no sources jar is published"));
    }

    @Test
    void fetchFailsApproximateStillUsed() throws IOException {
        // the git branch 2.0 approximates 2.0.5 - it is still used when the
        // on-demand fetch of the exact sources jar fails
        Path script = Fixtures.writeScript(base.resolve("fake-mvn-fail"), """
                #!/bin/sh
                echo "[ERROR] Could not find artifact org.example:lib-core:jar:sources:2.0.5 in central" >&2
                exit 1
                """);

        var config = TestConfigs.of(base.resolve("roots"), base.resolve("m2"),
                "show", 16, 256, List.of(), List.of(), true, List.of(script.toString()));
        var fetchLocator = newLocator(config);

        LocatedSources located = fetchLocator.locate(Gav.parse(Fixtures.GIT_BRANCH_GAV));
        assertTrue(located.origin().contains("git repo"), located.origin());
        assertFalse(located.exact());
    }
}
