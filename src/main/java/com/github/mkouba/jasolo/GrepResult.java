package com.github.mkouba.jasolo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Structured result of the {@code grepSource} tool.
 *
 * @param filename the path of the searched source file relative to its source root
 * @param version the version part of the requested GAV
 * @param origin where the source was resolved from
 * @param pattern the regular expression as requested
 * @param count the total number of matching lines
 * @param truncated whether the {@link #matches()} list was capped
 * @param matches the first matching lines, in file order
 * @param warnings warnings about the located sources, e.g. not an exact match
 *        of the requested version; omitted when empty
 */
public record GrepResult(String filename, String version, String origin, String pattern, int count, boolean truncated,
        List<Match> matches, @JsonInclude(Include.NON_EMPTY) List<String> warnings) {

    /**
     * A single matching line of the searched source file.
     *
     * @param line the 1-based line number
     * @param text the text of the line
     */
    record Match(int line, String text) {
    }
}
