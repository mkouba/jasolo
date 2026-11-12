package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStdioTestClient;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Runs the packaged production artifact (uber-jar or native executable, see
 * {@code mvn verify -Dprod}) over the stdio transport against the fixture git
 * roots and local Maven repository.
 */
class SourceLocatorToolsIT {

    static Path base;

    @BeforeAll
    static void createFixtures() {
        base = Fixtures.create();
    }

    @AfterAll
    static void deleteFixtures() {
        Fixtures.delete(base);
    }

    @Test
    void testTools() {
        try (McpStdioTestClient client = McpAssured.newStdioClient()
                .setEnvironment(Map.of(
                        "JASOLO_GIT_ROOTS", base.resolve("roots").toString(),
                        "JASOLO_MAVEN_LOCAL_REPO", base.resolve("m2").toString()))
                .build()
                .connect()) {
            client.when()
                    .toolsList(page -> {
                        assertEquals(4, page.size());
                        assertNotNull(page.findByName("getSource"));
                        assertNotNull(page.findByName("grepSource"));
                        assertNotNull(page.findByName("grepArtifact"));
                        assertNotNull(page.findByName("listClasses"));
                    })
                    // resolved from the git tag 2.0.0
                    .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV),
                            r -> {
                                assertFalse(r.isError());
                                JsonObject json = (JsonObject) r.structuredContent();
                                assertTrue(json.getString("origin").endsWith("ref 2.0.0"), json.encode());
                                assertTrue(json.getString("content").contains("marker: tag-2.0.0"), json.encode());
                                assertEquals(0, r.content().size());
                            })
                    // no git ref 3.1.0 - resolved from the m2 sources jar
                    .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Core", "gav",
                            Fixtures.M2_FALLBACK_GAV, "pattern", "marker"), r -> {
                                assertFalse(r.isError());
                                JsonObject json = (JsonObject) r.structuredContent();
                                assertTrue(json.getString("origin").contains("maven sources jar"), json.encode());
                                assertEquals(1, json.getInteger("count"));
                            })
                    // artifact-wide grep over the git-resolved sources
                    .toolsCall("grepArtifact", Map.of("gav", Fixtures.GIT_TAG_GAV, "pattern", "marker"), r -> {
                        assertFalse(r.isError());
                        JsonObject json = (JsonObject) r.structuredContent();
                        assertTrue(json.getString("origin").endsWith("ref 2.0.0"), json.encode());
                        assertEquals(2, json.getInteger("filesSearched"));
                        assertEquals(1, json.getInteger("filesWithMatches"));
                        JsonArray files = json.getJsonArray("files");
                        assertEquals(1, files.size());
                        assertEquals("org.example.libcore.Core", files.getJsonObject(0).getString("fqcn"));
                    })
                    // approximate match - read from the git branch 2.0, with a warning
                    .toolsCall("listClasses", Map.of("gav", Fixtures.GIT_BRANCH_GAV), r -> {
                        assertFalse(r.isError());
                        JsonObject json = (JsonObject) r.structuredContent();
                        assertEquals(new JsonArray().add("org.example.libcore.Big").add("org.example.libcore.Core"),
                                json.getJsonArray("fqcns"));
                        assertEquals(1, json.getJsonArray("warnings").size());
                    })
                    .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.MISSING_GAV),
                            r -> {
                                assertTrue(r.isError());
                                assertTrue(r.firstContent().asText().text().contains("No source resolver"));
                            })
                    .thenAssertResults();
        }
    }
}
