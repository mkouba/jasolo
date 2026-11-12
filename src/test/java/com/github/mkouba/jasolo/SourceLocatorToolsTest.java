package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@QuarkusTest
@TestProfile(JasoloTestProfile.class)
class SourceLocatorToolsTest {

    @Test
    void testGetSource() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                .toolsList(page -> {
                    assertEquals(3, page.size());
                    assertNotNull(page.findByName("getSource"));
                    assertNotNull(page.findByName("grepSource"));
                    assertNotNull(page.findByName("listClasses"));
                })
                // resolved from the git tag 2.0.0
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV),
                        r -> {
                            assertFalse(r.isError());
                            assertSource(r, "2.0.0", "org/example/libcore/Core.java", "ref 2.0.0", 5,
                                    "marker: tag-2.0.0");
                        })
                // no git ref 3.1.0 - resolved from the m2 sources jar
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.M2_FALLBACK_GAV),
                        r -> {
                            assertFalse(r.isError());
                            assertSource(r, "3.1.0", "org/example/libcore/Core.java", "maven sources jar", 5,
                                    "marker: m2-jar");
                        })
                // nested class resolves to its top-level source file
                .toolsCall("getSource",
                        Map.of("fqcn", "org.example.libcore.Core$Inner", "gav", Fixtures.GIT_TAG_GAV),
                        r -> {
                            assertFalse(r.isError());
                            assertSource(r, "2.0.0", "org/example/libcore/Core.java", "ref 2.0.0", 5,
                                    "marker: tag-2.0.0");
                        })
                // test sources are readable too
                .toolsCall("getSource",
                        Map.of("fqcn", "org.example.libcore.CoreTest", "gav", Fixtures.GIT_TAG_GAV),
                        r -> {
                            assertFalse(r.isError());
                            assertSource(r, "2.0.0", "org/example/libcore/CoreTest.java", "ref 2.0.0", 4,
                                    "class CoreTest");
                        })
                .thenAssertResults();
    }

    @Test
    void testWarnings() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                // no 2.0.5 tag and no 2.0.5 sources jar - read from the git branch 2.0, with a warning
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_BRANCH_GAV),
                        r -> {
                            assertFalse(r.isError());
                            JsonObject json = (JsonObject) r.structuredContent();
                            assertEquals("2.0.5", json.getString("version"));
                            assertTrue(json.getString("origin").endsWith("ref 2.0"), json.getString("origin"));
                            assertTrue(json.getString("content").contains("marker: tag-2.0.0"));
                            assertBranchWarning(json);
                        })
                .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_BRANCH_GAV,
                        "pattern", "marker"), r -> {
                            assertFalse(r.isError());
                            assertBranchWarning(r.structuredContent());
                        })
                .toolsCall("listClasses", Map.of("gav", Fixtures.GIT_BRANCH_GAV), r -> {
                    assertFalse(r.isError());
                    assertBranchWarning(r.structuredContent());
                })
                // the exact m2 sources jar wins over the approximate git branch - no warning
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.BRANCH_AND_M2_GAV),
                        r -> {
                            assertFalse(r.isError());
                            assertSource(r, "2.0.7", "org/example/libcore/Core.java", "maven sources jar", 5,
                                    "marker: m2-jar-2.0.7");
                        })
                .thenAssertResults();
    }

    @Test
    void testGetSourceErrors() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                // no resolver can locate the GAV
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.MISSING_GAV),
                        r -> {
                            assertTrue(r.isError());
                            assertTrue(r.firstContent().asText().text().contains("No source resolver"),
                                    r.firstContent().asText().text());
                        })
                // malformed GAV
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", "org.example:lib-core"),
                        r -> {
                            assertTrue(r.isError());
                            assertTrue(r.firstContent().asText().text().contains("Invalid GAV"));
                        })
                // malformed FQCN
                .toolsCall("getSource", Map.of("fqcn", "not a fqcn!", "gav", Fixtures.GIT_TAG_GAV),
                        r -> {
                            assertTrue(r.isError());
                            assertTrue(r.firstContent().asText().text().contains("Invalid fully qualified class name"));
                        })
                // class not present in the located sources
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Nope", "gav", Fixtures.GIT_TAG_GAV),
                        r -> {
                            assertTrue(r.isError());
                            assertTrue(r.firstContent().asText().text().contains("has no source file"));
                        })
                .thenAssertResults();
    }

    @Test
    void testListClasses() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                // git-resolved
                .toolsCall("listClasses", Map.of("gav", Fixtures.GIT_TAG_GAV), r -> {
                    assertFalse(r.isError());
                    assertListing(r.structuredContent(), "org.example:lib-core:2.0.0",
                            new JsonArray().add("org.example.libcore.Big").add("org.example.libcore.Core"));
                })
                // m2-resolved: module-info filtered out, src/main/java rooting stripped
                .toolsCall("listClasses", Map.of("gav", Fixtures.M2_ONLY_GAV), r -> {
                    assertFalse(r.isError());
                    assertListing(r.structuredContent(), "org.example:lib-extra:1.0.0",
                            new JsonArray().add("org.example.legacy.Legacy").add("org.example.libextra.Extra"));
                })
                .thenAssertResults();
    }

    @Test
    void testListClassesError() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                .toolsCall("listClasses", Map.of("gav", Fixtures.MISSING_GAV), r -> {
                    assertTrue(r.isError());
                    assertTrue(r.firstContent().asText().text().contains("No source resolver"));
                })
                .thenAssertResults();
    }

    @Test
    void testGetSourceLineRange() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                // lines 3-4 of Core.java
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV,
                        "startLine", 3, "endLine", 4), r -> {
                            assertFalse(r.isError());
                            JsonObject json = (JsonObject) r.structuredContent();
                            assertEquals(5, json.getInteger("totalLines"));
                            assertEquals("public class Core {\n    // marker: tag-2.0.0",
                                    json.getString("content"));
                        })
                // endLine is clamped to the end of the file
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV,
                        "startLine", 4, "endLine", 99), r -> {
                            assertFalse(r.isError());
                            JsonObject json = (JsonObject) r.structuredContent();
                            assertEquals("    // marker: tag-2.0.0\n}", json.getString("content"));
                        })
                // startLine beyond the end of the file
                .toolsCall("getSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV,
                        "startLine", 42), r -> {
                            assertTrue(r.isError());
                            assertTrue(r.firstContent().asText().text().contains("beyond the end of the file"),
                                    r.firstContent().asText().text());
                        })
                .thenAssertResults();
    }

    @Test
    void testGrepSource() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV,
                        "pattern", "marker"), r -> {
                            assertFalse(r.isError());
                            JsonObject json = (JsonObject) r.structuredContent();
                            assertEquals("org/example/libcore/Core.java", json.getString("filename"));
                            assertEquals("2.0.0", json.getString("version"));
                            assertEquals("marker", json.getString("pattern"));
                            assertEquals(1, json.getInteger("count"));
                            assertFalse(json.getBoolean("truncated"));
                            JsonArray matches = json.getJsonArray("matches");
                            assertEquals(1, matches.size());
                            assertEquals(4, matches.getJsonObject(0).getInteger("line"));
                            assertTrue(matches.getJsonObject(0).getString("text").contains("marker: tag-2.0.0"));
                        })
                // case-insensitive matching
                .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV,
                        "pattern", "MARKER", "ignoreCase", true), r -> {
                            assertFalse(r.isError());
                            assertEquals(1, ((JsonObject) r.structuredContent()).getInteger("count"));
                        })
                // no matches
                .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV,
                        "pattern", "no-such-thing"), r -> {
                            assertFalse(r.isError());
                            JsonObject json = (JsonObject) r.structuredContent();
                            assertEquals(0, json.getInteger("count"));
                            assertEquals(0, json.getJsonArray("matches").size());
                        })
                // the match list is capped
                .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Big", "gav", Fixtures.GIT_TAG_GAV,
                        "pattern", "filler"), r -> {
                            assertFalse(r.isError());
                            JsonObject json = (JsonObject) r.structuredContent();
                            assertEquals(600, json.getInteger("count"));
                            assertTrue(json.getBoolean("truncated"));
                            assertEquals(SourceLocatorTools.MAX_MATCHES, json.getJsonArray("matches").size());
                        })
                // invalid pattern
                .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.GIT_TAG_GAV,
                        "pattern", "["), r -> {
                            assertTrue(r.isError());
                            assertTrue(r.firstContent().asText().text().contains("Invalid pattern"));
                        })
                // class not present in the located sources
                .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Nope", "gav", Fixtures.GIT_TAG_GAV,
                        "pattern", "x"), r -> {
                            assertTrue(r.isError());
                            assertTrue(r.firstContent().asText().text().contains("has no source file"));
                        })
                // resolved from the m2 sources jar
                .toolsCall("grepSource", Map.of("fqcn", "org.example.libcore.Core", "gav", Fixtures.M2_FALLBACK_GAV,
                        "pattern", "m2-jar"), r -> {
                            assertFalse(r.isError());
                            JsonObject json = (JsonObject) r.structuredContent();
                            assertEquals(1, json.getInteger("count"));
                            assertTrue(json.getString("origin").contains("maven sources jar"));
                        })
                .thenAssertResults();
    }

    /**
     * Asserts the structured content of a successful, exactly matched getSource
     * call; the structured content is not mirrored as text content.
     */
    private static void assertSource(io.quarkiverse.mcp.server.ToolResponse r, String version, String filename,
            String origin, int totalLines, String contentMarker) {
        assertNotNull(r.structuredContent());
        assertTrue(r.structuredContent() instanceof JsonObject,
                "not a JsonObject: " + r.structuredContent());
        JsonObject json = (JsonObject) r.structuredContent();
        assertEquals(filename, json.getString("filename"));
        assertEquals(version, json.getString("version"));
        assertTrue(json.getString("origin").contains(origin), json.getString("origin"));
        assertEquals(totalLines, json.getInteger("totalLines"));
        assertTrue(json.getString("content").contains(contentMarker),
                json.getString("content"));
        // structured content only - the content array is present but empty
        assertNotNull(r.content());
        assertEquals(0, r.content().size());
        // exact match - the empty warnings list is omitted
        assertFalse(json.containsKey("warnings"), json.encode());
    }

    private static void assertBranchWarning(Object structuredContent) {
        JsonObject json = (JsonObject) structuredContent;
        JsonArray warnings = json.getJsonArray("warnings");
        assertNotNull(warnings, json.encode());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getString(0).contains("No tag matches version 2.0.5"), warnings.getString(0));
    }

    private static void assertListing(Object structuredContent, String gav, JsonArray expectedFqcns) {
        assertNotNull(structuredContent);
        assertTrue(structuredContent instanceof JsonObject, "not a JsonObject: " + structuredContent);
        JsonObject json = (JsonObject) structuredContent;
        assertEquals(gav, json.getString("gav"));
        assertEquals(expectedFqcns.size(), json.getInteger("count"));
        assertEquals(expectedFqcns, json.getJsonArray("fqcns"));
    }
}
