package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        var config = TestConfigs.of(base.resolve("roots"), base.resolve("m2"));
        locator = new SourceLocator(List.of(
                new GitRepoResolver(config, new GitRepoIndex(config), List.of(new ShowGitAccessStrategy())),
                new MavenSourcesResolver(config)));
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
    }

    @Test
    void resolverOrder() {
        assertEquals(List.of("git-repo", "m2-sources-jar"), locator.resolverNames());
    }
}
