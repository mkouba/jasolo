package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitRepoIndexTest {

    Path base;
    GitRepoIndex index;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createTempDirectory("jasolo-index-test");
        Fixtures.createGitRoot(base);
        index = new GitRepoIndex(TestConfigs.of(base.resolve("roots"), null));
    }

    @AfterEach
    void tearDown() {
        Fixtures.delete(base);
    }

    @Test
    void indexContainsModuleWithInheritedCoordinates() {
        Map<String, GitRepoIndex.ModuleLocation> map = index.index();
        GitRepoIndex.ModuleLocation module = map.get(Fixtures.GIT_GA);
        assertNotNull(module, "expected " + Fixtures.GIT_GA + " in " + map.keySet());
        assertEquals(base.resolve("roots/lib-parent"), module.repoDir());
        assertEquals("lib-core", module.moduleDir());
    }

    @Test
    void pomPackagingNotIndexed() {
        // the root pom is packaging=pom and must not produce an entry
        assertNull(index.index().get("org.example:lib-parent"));
    }

    @Test
    void emptyRoots() {
        GitRepoIndex empty = new GitRepoIndex(TestConfigs.of(null, null));
        assertTrue(empty.index().isEmpty());
    }

    @Test
    void missingRootIsIgnored() {
        GitRepoIndex missing = new GitRepoIndex(TestConfigs.of(base.resolve("no-such-root"), null));
        assertTrue(missing.index().isEmpty());
    }

    @Test
    void nestedRepoIndexed() {
        Map<String, GitRepoIndex.ModuleLocation> map = index.index();
        GitRepoIndex.ModuleLocation module = map.get("org.example:other-lib");
        assertNotNull(module, "expected org.example:other-lib in " + map.keySet());
        assertEquals(base.resolve("roots/group/other-lib"), module.repoDir());
        assertEquals("", module.moduleDir());
    }

    @Test
    void repoInsideIndexedRepoNotIndexed() {
        // a repo nested inside an indexed repo is not descended into
        assertFalse(index.index().containsKey("org.example:nested-repo"));
    }

    @Test
    void rootItselfIsRepo() throws IOException {
        Path repo = Files.createTempDirectory("jasolo-root-repo-test");
        try {
            Fixtures.createStandaloneRepo(repo, "org.example", "standalone-lib", "1.0.0-SNAPSHOT");
            GitRepoIndex rootIndex = new GitRepoIndex(TestConfigs.of(repo, null));
            GitRepoIndex.ModuleLocation module = rootIndex.index().get("org.example:standalone-lib");
            assertNotNull(module, "expected the root itself to be indexed");
            assertEquals(repo, module.repoDir());
            assertEquals("", module.moduleDir());
        } finally {
            Fixtures.delete(repo);
        }
    }

    @Test
    void shallowerRepoWinsDuplicateCoordinates() throws IOException {
        Path root = base.resolve("roots");
        // the deeper repo sorts first lexicographically - the shallower one must win
        Fixtures.createStandaloneRepo(root.resolve("a/deep-lib"), "org.example", "dup-lib", "1.0.0-SNAPSHOT");
        Fixtures.createStandaloneRepo(root.resolve("z-lib"), "org.example", "dup-lib", "1.0.0-SNAPSHOT");
        GitRepoIndex.ModuleLocation module = index.index().get("org.example:dup-lib");
        assertNotNull(module, "expected org.example:dup-lib in the index");
        assertEquals(root.resolve("z-lib"), module.repoDir());
    }

    @Test
    void excludedRelativePathNotScanned() throws IOException {
        Path root = base.resolve("roots");
        Fixtures.createStandaloneRepo(root.resolve("excluded/hidden-lib"), "org.example", "hidden-lib", "1.0.0-SNAPSHOT");
        Fixtures.createStandaloneRepo(root.resolve("excluded/visible-lib"), "org.example", "visible-lib", "1.0.0-SNAPSHOT");
        // no exclusions configured - both repos are found
        assertNotNull(index.index().get("org.example:hidden-lib"));
        assertNotNull(index.index().get("org.example:visible-lib"));
        // an exact relative path excludes only that repo
        GitRepoIndex oneRepo = new GitRepoIndex(
                TestConfigs.of(root, null, "show", 16, 256, List.of(), List.of("excluded/hidden-lib")));
        assertFalse(oneRepo.index().containsKey("org.example:hidden-lib"));
        assertNotNull(oneRepo.index().get("org.example:visible-lib"));
        // a relative path excludes the whole subtree
        GitRepoIndex subtree = new GitRepoIndex(
                TestConfigs.of(root, null, "show", 16, 256, List.of(), List.of("excluded")));
        assertFalse(subtree.index().containsKey("org.example:hidden-lib"));
        assertFalse(subtree.index().containsKey("org.example:visible-lib"));
    }
}
