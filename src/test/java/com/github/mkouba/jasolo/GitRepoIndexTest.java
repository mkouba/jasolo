package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
