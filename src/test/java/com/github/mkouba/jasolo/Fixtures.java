package com.github.mkouba.jasolo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Test fixtures: a multi-module git repo under a roots directory and a local
 * Maven repository with sources jars.
 * <p>
 * Git fixture layout (repo {@code roots/lib-parent}):
 * <ul>
 * <li>root pom {@code org.example:lib-parent:2.0.1-SNAPSHOT}, packaging pom</li>
 * <li>module {@code org.example:lib-core} (groupId/version inherited from parent)</li>
 * <li>tag {@code 2.0.0} and branch {@code 2.0} point at the first commit
 * ({@code Core} marker: {@code tag-2.0.0}), {@code main} has a second commit
 * ({@code Core} marker: {@code main-branch})</li>
 * </ul>
 * Additional repositories under {@code roots}:
 * <ul>
 * <li>{@code roots/group/other-lib} ({@code org.example:other-lib}) - nested
 * two levels below the root, found by the recursive scan</li>
 * <li>{@code roots/lib-parent/integration/nested-repo}
 * ({@code org.example:nested-repo}) - inside an indexed repo, never
 * indexed</li>
 * </ul>
 */
final class Fixtures {

    static final String GIT_GA = "org.example:lib-core";
    static final String GIT_TAG_GAV = "org.example:lib-core:2.0.0";
    static final String GIT_BRANCH_GAV = "org.example:lib-core:2.0.5";
    static final String GIT_SNAPSHOT_GAV = "org.example:lib-core:2.0.1-SNAPSHOT";
    static final String M2_FALLBACK_GAV = "org.example:lib-core:3.1.0";
    // no 2.0.7 tag - the git branch 2.0 is an approximate match, the m2 sources jar an exact one
    static final String BRANCH_AND_M2_GAV = "org.example:lib-core:2.0.7";
    static final String M2_ONLY_GA = "org.example:lib-extra";
    static final String M2_ONLY_GAV = "org.example:lib-extra:1.0.0";
    static final String MISSING_GAV = "org.example:missing:1.0.0";

    private Fixtures() {
    }

    static Path create() {
        try {
            Path base = Files.createTempDirectory("jasolo-test");
            createGitRoot(base);
            createM2(base);
            return base;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void createGitRoot(Path base) throws IOException {
        Path repo = base.resolve("roots/lib-parent");
        write(repo.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.example</groupId>
                    <artifactId>lib-parent</artifactId>
                    <version>2.0.1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>lib-core</module>
                    </modules>
                </project>
                """);
        write(repo.resolve("lib-core/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.example</groupId>
                        <artifactId>lib-parent</artifactId>
                        <version>2.0.1-SNAPSHOT</version>
                    </parent>
                    <artifactId>lib-core</artifactId>
                </project>
                """);
        write(repo.resolve("lib-core/src/main/java/org/example/libcore/Core.java"), coreSource("tag-2.0.0"));
        write(repo.resolve("lib-core/src/test/java/org/example/libcore/CoreTest.java"), """
                package org.example.libcore;

                public class CoreTest {
                }
                """);
        // a large generated class for the line-range and grep tests
        StringBuilder big = new StringBuilder("""
                package org.example.libcore;

                public class Big {
                """);
        for (int i = 1; i <= 600; i++) {
            big.append("    // filler line ").append(i).append('\n');
        }
        big.append("}\n");
        write(repo.resolve("lib-core/src/main/java/org/example/libcore/Big.java"), big.toString());

        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "jasolo test");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "release 2.0.0");
        git(repo, "tag", "2.0.0");
        git(repo, "branch", "2.0");

        // main moves on - snapshot versions resolve to main
        write(repo.resolve("lib-core/src/main/java/org/example/libcore/Core.java"), coreSource("main-branch"));
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "main branch work");

        // a repo nested more than one level below the root - found by the
        // recursive scan
        createStandaloneRepo(base.resolve("roots/group/other-lib"), "org.example", "other-lib", "1.0.0-SNAPSHOT");

        // a repo inside an indexed repo - must NOT be indexed (created after
        // the commits of the enclosing repo, so it is not committed there)
        createStandaloneRepo(repo.resolve("integration/nested-repo"), "org.example", "nested-repo", "1.0.0-SNAPSHOT");
    }

    /**
     * A single-module git repository producing {@code groupId:artifactId}.
     */
    static void createStandaloneRepo(Path repo, String groupId, String artifactId, String version) throws IOException {
        write(repo.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "jasolo test");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "initial");
    }

    static void createM2(Path base) throws IOException {
        Path m2 = base.resolve("m2");
        writeSourcesJar(m2, "org.example", "lib-core", "2.0.0",
                Map.of("org/example/libcore/Core.java", coreSource("m2-jar")));
        writeSourcesJar(m2, "org.example", "lib-core", "3.1.0",
                Map.of("org/example/libcore/Core.java", coreSource("m2-jar")));
        writeSourcesJar(m2, "org.example", "lib-core", "2.0.7",
                Map.of("org/example/libcore/Core.java", coreSource("m2-jar-2.0.7")));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
        entries.put("org/example/libextra/Extra.java", "package org.example.libextra;\npublic class Extra {}\n");
        // some sources jars are rooted at src/main/java
        entries.put("src/main/java/org/example/legacy/Legacy.java",
                "package org.example.legacy;\npublic class Legacy {}\n");
        // must be filtered out of the fqcn listing
        entries.put("module-info.java", "module org.example {}\n");
        writeSourcesJar(m2, "org.example", "lib-extra", "1.0.0", entries);
    }

    static String coreSource(String marker) {
        return """
                package org.example.libcore;

                public class Core {
                    // marker: %s
                }
                """.formatted(marker);
    }

    static void delete(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    static void writeSourcesJar(Path m2, String groupId, String artifactId, String version,
            Map<String, String> entries) throws IOException {
        Path dir = m2.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(artifactId + "-" + version + "-sources.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    static void git(Path repo, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Processes.exec(null, command);
    }
}
