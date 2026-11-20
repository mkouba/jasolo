package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MavenSourcesFetcherTest {

    Path base;
    MavenSourcesFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createTempDirectory("jasolo-fetch-test");
        Fixtures.createM2(base);
    }

    @AfterEach
    void tearDown() {
        Fixtures.delete(base);
    }

    @Test
    void disabledByDefault() {
        fetcher = new MavenSourcesFetcher(TestConfigs.of(null, base.resolve("m2")));
        assertFalse(fetcher.enabled());
    }

    @Test
    void commandNotFound() {
        fetcher = new MavenSourcesFetcher(TestConfigs.of(null, base.resolve("m2"),
                "show", 16, 256, java.util.List.of(), java.util.List.of(), true,
                java.util.List.of("no-such-mvn-command")));
        var result = fetcher.fetch(Gav.parse(Fixtures.MISSING_GAV));
        assertFalse(result.fetched());
        // actionable: says what failed and how to fix it
        assertTrue(result.detail().contains("unable to execute 'no-such-mvn-command'"), result.detail());
        assertTrue(result.detail().contains("PATH of the jasolo process"), result.detail());
        assertTrue(result.detail().contains("jasolo.maven.fetch.command"), result.detail());
        assertTrue(result.detail().contains("mvnd"), result.detail());
    }

    @Test
    void successVerifiesJar() throws IOException {
        Path jar = base.resolve("fetched-sources.jar");
        Fixtures.writeJar(jar, java.util.Map.of("org/example/missing/Missing.java",
                "package org.example.missing;\npublic class Missing {}\n"));
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
        fetcher = new MavenSourcesFetcher(TestConfigs.of(null, base.resolve("m2"),
                "show", 16, 256, java.util.List.of(), java.util.List.of(), true,
                java.util.List.of(script.toString())));

        var result = fetcher.fetch(Gav.parse(Fixtures.MISSING_GAV));
        assertTrue(result.fetched(), result.detail());
        // the fetched jar is in place and the m2 resolver can locate it
        assertTrue(Files.isRegularFile(base.resolve(
                "m2/org/example/missing/1.0.0/missing-1.0.0-sources.jar")));
    }

    @Test
    void commandAssembly() throws IOException {
        // the fetch command is <configured command...> followed by the fixed
        // dependency:get arguments - a script records what was actually executed
        Path jar = base.resolve("fetched-sources.jar");
        Fixtures.writeJar(jar, java.util.Map.of("org/example/missing/Missing.java",
                "package org.example.missing;\npublic class Missing {}\n"));
        Path captured = base.resolve("captured-args.txt");
        Path script = Fixtures.writeScript(base.resolve("fake-mvn"), """
                #!/bin/sh
                printf '%%s\n' "$@" > %s
                repo=""
                for a in "$@"; do
                  case "$a" in
                    -Dmaven.repo.local=*) repo="${a#-Dmaven.repo.local=}";;
                  esac
                done
                mkdir -p "$repo/org/example/missing/1.0.0"
                cp %s "$repo/org/example/missing/1.0.0/missing-1.0.0-sources.jar"
                """.formatted(captured, jar));
        fetcher = new MavenSourcesFetcher(TestConfigs.of(null, base.resolve("m2"),
                "show", 16, 256, java.util.List.of(), java.util.List.of(), true,
                java.util.List.of(script.toString(), "--lead-arg")));

        var result = fetcher.fetch(Gav.parse(Fixtures.MISSING_GAV));
        assertTrue(result.fetched(), result.detail());
        // the configured command (with its leading argument) comes first, then
        // the fixed dependency:get arguments
        assertEquals(java.util.List.of(
                "--lead-arg",
                "-B", "-ntp", "dependency:get",
                "-Dartifact=org.example:missing:1.0.0:jar:sources",
                "-Dtransitive=false",
                "-Dmaven.repo.local=" + base.resolve("m2")),
                Files.readAllLines(captured));
    }
}
