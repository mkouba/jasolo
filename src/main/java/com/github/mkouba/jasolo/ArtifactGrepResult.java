package com.github.mkouba.jasolo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Structured result of the {@code grepArtifact} tool.
 *
 * @param gav the requested GAV
 * @param origin where the sources were resolved from
 * @param pattern the regular expression as requested
 * @param filesSearched the number of source files searched
 * @param filesWithMatches the number of files with at least one matching line
 * @param count the total number of matching lines across all files
 * @param truncated whether the collected match lists were capped
 * @param files the files with at least one matching line, in class name order
 * @param warnings warnings about the located sources, e.g. not an exact match
 *        of the requested version; omitted when empty
 */
public record ArtifactGrepResult(String gav, String origin, String pattern, int filesSearched,
        int filesWithMatches, int count, boolean truncated, List<FileResult> files,
        @JsonInclude(Include.NON_EMPTY) List<String> warnings) {

    /**
     * The matching lines of a single source file.
     *
     * @param fqcn the fully qualified name of the class
     * @param count the total number of matching lines in the file
     * @param truncated whether the {@link #matches()} list was capped
     * @param matches the first matching lines, in file order
     */
    record FileResult(String fqcn, int count, boolean truncated, List<GrepResult.Match> matches) {
    }
}
