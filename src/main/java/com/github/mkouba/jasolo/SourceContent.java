package com.github.mkouba.jasolo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Structured result of the {@code getSource} tool.
 *
 * @param filename the path of the source file relative to its source root,
 *        e.g. {@code io/quarkus/runtime/Quarkus.java}
 * @param version the version part of the requested GAV
 * @param origin where the source was resolved from, e.g.
 *        {@code git repo /home/me/source/quarkus, module core/runtime, ref 3.27.5.1}
 *        or the path of the Maven sources jar
 * @param content the content of the source file - the whole file, or the
 *        requested line range when {@code startLine}/{@code endLine} were given
 * @param totalLines the total number of lines of the source file
 * @param warnings warnings about the located sources, e.g. not an exact match
 *        of the requested version; omitted when empty
 */
public record SourceContent(String filename, String version, String origin, String content, int totalLines,
        @JsonInclude(Include.NON_EMPTY) List<String> warnings) {
}
