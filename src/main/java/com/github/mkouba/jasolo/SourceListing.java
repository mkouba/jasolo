package com.github.mkouba.jasolo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Structured result of the {@code listClasses} tool.
 *
 * @param gav the requested GAV
 * @param origin where the sources were resolved from
 * @param count the number of listed classes
 * @param fqcns the fully qualified names of the main source files
 * @param warnings warnings about the located sources, e.g. not an exact match
 *        of the requested version; omitted when empty
 */
public record SourceListing(String gav, String origin, int count, List<String> fqcns,
        @JsonInclude(Include.NON_EMPTY) List<String> warnings) {
}
