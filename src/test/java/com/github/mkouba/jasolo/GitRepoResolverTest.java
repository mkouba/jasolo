package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitRepoResolverTest {

    Path base;
    GitRepoResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createTempDirectory("jasolo-git-test");
        Fixtures.createGitRoot(base);
        resolver = new GitRepoResolver(TestConfigs.of(base.resolve("roots"), null),
                new GitRepoIndex(TestConfigs.of(base.resolve("roots"), null)),
                List.of(new ShowGitAccessStrategy()));
    }

    @AfterEach
    void tearDown() {
        Fixtures.delete(base);
    }

    @Test
    void readFromTag() {
        Optional<LocatedSources> located = resolver.locate(Gav.parse(Fixtures.GIT_TAG_GAV));
        assertTrue(located.isPresent());
        assertEquals("git-repo", resolver.name());
        assertTrue(located.get().origin().contains("ref 2.0.0"));
        // a tag is an exact match
        assertTrue(located.get().exact());
        assertEquals(List.of(), located.get().warnings());

        Optional<String> content = located.get().read("org.example.libcore.Core");
        assertTrue(content.isPresent());
        assertTrue(content.get().contains("marker: tag-2.0.0"),
                "expected tag content, got: " + content.get());
    }

    @Test
    void readFromBranchViaRefPattern() {
        // no 2.0.5 tag exists - the {major}.{minor} pattern must resolve branch 2.0
        Optional<LocatedSources> located = resolver.locate(Gav.parse(Fixtures.GIT_BRANCH_GAV));
        assertTrue(located.isPresent());
        assertTrue(located.get().origin().contains("ref 2.0"));
        // a release version read from a branch is only an approximate match
        assertFalse(located.get().exact());
        assertEquals(1, located.get().warnings().size());
        String warning = located.get().warnings().get(0);
        assertTrue(warning.contains("No tag matches version 2.0.5"), warning);
        assertTrue(warning.contains("branch 2.0"), warning);
        assertTrue(warning.contains("git fetch --tags"), warning);
        Optional<String> content = located.get().read("org.example.libcore.Core");
        assertTrue(content.isPresent());
        assertTrue(content.get().contains("marker: tag-2.0.0"));
    }

    @Test
    void refCacheRefreshedOnBranchMatch() {
        // no 2.0.5 tag - branch 2.0 matches approximately (and the ref set is cached)
        Optional<LocatedSources> located = resolver.locate(Gav.parse(Fixtures.GIT_BRANCH_GAV));
        assertTrue(located.isPresent());
        assertFalse(located.get().exact());

        // the tag appears in the repository (e.g. after git fetch --tags)
        Fixtures.git(base.resolve("roots/lib-parent"), "tag", "2.0.5");

        // an approximate match refreshes the cached ref set - the new tag wins
        located = resolver.locate(Gav.parse(Fixtures.GIT_BRANCH_GAV));
        assertTrue(located.isPresent());
        assertTrue(located.get().exact());
        assertTrue(located.get().origin().endsWith("ref 2.0.5"), located.get().origin());
        assertEquals(List.of(), located.get().warnings());
    }

    @Test
    void snapshotResolvesToDefaultBranch() {
        Optional<LocatedSources> located = resolver.locate(Gav.parse(Fixtures.GIT_SNAPSHOT_GAV));
        assertTrue(located.isPresent());
        assertTrue(located.get().origin().contains("ref main"));
        // the default branch is the expected ref of a snapshot - no warning
        assertTrue(located.get().exact());
        assertEquals(List.of(), located.get().warnings());
        Optional<String> content = located.get().read("org.example.libcore.Core");
        assertTrue(content.isPresent());
        assertTrue(content.get().contains("marker: main-branch"));
    }

    @Test
    void readTestSource() {
        Optional<LocatedSources> located = resolver.locate(Gav.parse(Fixtures.GIT_TAG_GAV));
        assertTrue(located.isPresent());
        assertTrue(located.get().read("org.example.libcore.CoreTest").isPresent());
        assertTrue(located.get().read("org.example.libcore.Nope").isEmpty());
    }

    @Test
    void listFqcns() {
        Optional<LocatedSources> located = resolver.locate(Gav.parse(Fixtures.GIT_TAG_GAV));
        assertTrue(located.isPresent());
        assertEquals(List.of("org.example.libcore.Big", "org.example.libcore.Core"),
                located.get().listFqcns());
    }

    @Test
    void locateWithoutMatchingRef() {
        assertTrue(resolver.locate(Gav.parse("org.example:lib-core:1.0.0")).isEmpty());
    }

    @Test
    void refCacheRefreshedOnMiss() {
        // no 9.9.9 tag or branch - the resolver declines
        assertTrue(resolver.locate(Gav.parse("org.example:lib-core:9.9.9")).isEmpty());

        // a new tag appears in the repository (e.g. after git fetch)
        Fixtures.git(base.resolve("roots/lib-parent"), "tag", "9.9.9");

        // the cached ref set is refreshed on miss - no restart needed
        Optional<LocatedSources> located = resolver.locate(Gav.parse("org.example:lib-core:9.9.9"));
        assertTrue(located.isPresent());
        assertTrue(located.get().origin().contains("ref 9.9.9"));
    }

    @Test
    void locateUnknownGa() {
        assertTrue(resolver.locate(Gav.parse(Fixtures.MISSING_GAV)).isEmpty());
    }

    @Test
    void validateFailsOnUnmetPrerequisites() {
        // validation happens in the constructor - a strategy with unmet
        // prerequisites fails the construction
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new GitRepoResolver(
                TestConfigs.of(base.resolve("roots"), null),
                new GitRepoIndex(TestConfigs.of(base.resolve("roots"), null)),
                List.of(new BrokenStrategy())));
        assertTrue(e.getMessage().contains("prerequisites not met"));
    }

    @Test
    void validateSkippedWithoutRoots() {
        // no git roots configured - the git resolver is disabled and the
        // strategy is never used, so its failing validation must be ignored
        assertDoesNotThrow(() -> new GitRepoResolver(TestConfigs.of(null, null),
                new GitRepoIndex(TestConfigs.of(null, null)), List.of(new BrokenStrategy())));
    }

    @Test
    void validateUnknownMode() {
        // the strategy is selected in the constructor - an unknown access
        // mode fails the construction
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new GitRepoResolver(
                TestConfigs.of(base.resolve("roots"), null, "bogus"),
                new GitRepoIndex(TestConfigs.of(base.resolve("roots"), null, "bogus")),
                List.of(new ShowGitAccessStrategy())));
        assertTrue(e.getMessage().contains("No git access strategy with id 'bogus'"));
    }

    @Test
    void validateFailsWhenRootDoesNotExist() {
        // a configured root that does not exist on disk fails the construction
        Path missingRoot = base.resolve("nonexistent-root");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new GitRepoResolver(
                TestConfigs.of(missingRoot, null),
                new GitRepoIndex(TestConfigs.of(missingRoot, null)),
                List.of(new ShowGitAccessStrategy())));
        assertTrue(e.getMessage().contains("git root does not exist"));
        assertTrue(e.getMessage().contains(missingRoot.toString()));
    }

    private static final class BrokenStrategy extends ShowGitAccessStrategy {

        @Override
        public void validate() {
            throw new IllegalStateException("prerequisites not met");
        }
    }
}
