package com.github.mkouba.jasolo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;

/**
 * The MCP tools of jasolo.
 */
public class SourceLocatorTools {

    private static final Logger LOG = Logger.getLogger(SourceLocatorTools.class);

    /**
     * A simple class name segment: starts with a letter/underscore/$, then
     * letters, digits, underscores or dollar signs.
     */
    private static final Pattern FQCN_PATTERN = Pattern
            .compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");

    /**
     * Safety limit for the {@code grepSource} match list.
     */
    static final int MAX_MATCHES = 500;

    @Inject
    SourceLocator locator;

    @Tool(name = "getSource", description = "Get the Java source file of a class in the sources of the given Maven "
            + "artifact, resolved from a local git clone or the Maven sources jar in the local repository. "
            + "For dependency artifacts only - not for files of the project currently being worked on.",
            structuredContent = true)
    SourceContent getSource(
            @ToolArg(description = "Fully qualified class name; a nested class resolves to its top-level source file") String fqcn,
            @ToolArg(description = "Maven coordinates: groupId:artifactId:version") String gav,
            @ToolArg(description = "First line to return, 1-based inclusive", required = false) Integer startLine,
            @ToolArg(description = "Last line to return, 1-based inclusive; clamped to the end of the file. "
                    + "Without startLine it caps the response to the first lines of the file: a totalLines "
                    + "greater than endLine means the content was truncated - continue with startLine",
                    required = false) Integer endLine) {
        SourceFile file = readSource(fqcn, gav);
        String[] lines = lines(file.content());
        String content;
        if (startLine == null && endLine == null) {
            content = file.content();
        } else {
            int start = startLine == null ? 1 : startLine;
            if (start < 1) {
                throw new ToolCallException("startLine must be >= 1: " + startLine);
            }
            if (start > lines.length) {
                throw new ToolCallException("startLine " + start + " is beyond the end of the file ("
                        + lines.length + " lines)");
            }
            int end = endLine == null ? lines.length : Math.min(endLine, lines.length);
            if (end < start) {
                throw new ToolCallException(
                        "endLine must not be before startLine: startLine=" + start + ", endLine=" + endLine);
            }
            content = String.join("\n", Arrays.asList(lines).subList(start - 1, end));
        }
        return new SourceContent(file.filename(), file.gav().version(), file.sources().origin(), content,
                lines.length, file.sources().warnings());
    }

    @Tool(name = "grepSource", description = "Search the source file of a class with a regular expression and get "
            + "the matching lines with their line numbers; the source is resolved like in getSource.",
            structuredContent = true)
    GrepResult grepSource(
            @ToolArg(description = "Fully qualified class name; a nested class resolves to its top-level source file") String fqcn,
            @ToolArg(description = "Maven coordinates: groupId:artifactId:version") String gav,
            @ToolArg(description = "Regular expression (java.util.regex syntax)") String pattern,
            @ToolArg(description = "Case-insensitive matching", required = false) Boolean ignoreCase) {
        SourceFile file = readSource(fqcn, gav);
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, Boolean.TRUE.equals(ignoreCase) ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolCallException("Invalid pattern '" + pattern + "': " + e.getMessage());
        }
        List<GrepResult.Match> matches = new ArrayList<>();
        int count = 0;
        String[] lines = lines(file.content());
        for (int i = 0; i < lines.length; i++) {
            if (compiled.matcher(lines[i]).find()) {
                count++;
                if (matches.size() < MAX_MATCHES) {
                    matches.add(new GrepResult.Match(i + 1, lines[i]));
                }
            }
        }
        return new GrepResult(file.filename(), file.gav().version(), file.sources().origin(), pattern, count,
                count > matches.size(), matches, file.sources().warnings());
    }

    @Tool(name = "listClasses", description = "List the fully qualified names of the main Java source files "
            + "of the given Maven artifact, resolved from a local git clone or the local Maven sources jar.",
            structuredContent = true)
    SourceListing listClasses(
            @ToolArg(description = "Maven coordinates: groupId:artifactId:version") String gav) {
        Gav parsed = parseGav(gav);
        LocatedSources sources = locate(parsed);
        List<String> fqcns = sources.listFqcns();
        return new SourceListing(parsed.toString(), sources.origin(), fqcns.size(), fqcns,
                sources.warnings());
    }

    /**
     * A located and read source file, plus the metadata shared by the tool results.
     *
     * @param filename the path of the source file relative to its source root
     * @param gav the parsed Maven coordinates
     * @param sources the located sources the file was read from
     * @param content the whole content of the file
     */
    private record SourceFile(String filename, Gav gav, LocatedSources sources, String content) {
    }

    private SourceFile readSource(String fqcn, String gav) {
        String normalized = normalizeFqcn(fqcn);
        Gav parsedGav = parseGav(gav);
        LocatedSources sources = locate(parsedGav);
        String content = sources.read(normalized).orElseThrow(() -> new ToolCallException(
                "Class " + normalized + " has no source file in " + sources.origin()
                        + ". Use the listClasses tool to list the available classes."));
        return new SourceFile(normalized.replace('.', '/') + ".java", parsedGav, sources, content);
    }

    /**
     * Splits content into lines; a trailing line terminator does not produce
     * an extra empty line.
     */
    private static String[] lines(String content) {
        String[] all = content.split("\\R", -1);
        int total = all.length;
        if (total > 1 && all[total - 1].isEmpty()) {
            total--;
        }
        return total == all.length ? all : Arrays.copyOf(all, total);
    }

    private static Gav parseGav(String gav) {
        try {
            return Gav.parse(gav);
        } catch (IllegalArgumentException e) {
            throw new ToolCallException(e.getMessage());
        }
    }

    private LocatedSources locate(Gav gav) {
        try {
            LocatedSources located = locator.locate(gav);
            LOG.debugf("Located %s via %s", gav, located.origin());
            return located;
        } catch (SourceNotFoundException e) {
            throw new ToolCallException(e.getMessage());
        }
    }

    static String normalizeFqcn(String fqcn) {
        if (fqcn == null) {
            throw new ToolCallException("fqcn must not be null");
        }
        String normalized = fqcn.trim();
        // a nested class shares the source file of its top-level class
        int nested = normalized.indexOf('$');
        if (nested > 0) {
            normalized = normalized.substring(0, nested);
        }
        if (!FQCN_PATTERN.matcher(normalized).matches()) {
            throw new ToolCallException(
                    "Invalid fully qualified class name '" + fqcn + "', e.g. io.quarkus.runtime.Quarkus");
        }
        return normalized;
    }
}
