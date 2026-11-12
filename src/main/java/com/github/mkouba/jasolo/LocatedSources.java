package com.github.mkouba.jasolo;

import java.util.List;
import java.util.Optional;

/**
 * Sources of a single artifact, as located by a {@link SourceResolver}.
 */
public interface LocatedSources {

    /**
     * @return human-readable description of where the sources come from,
     *         e.g. {@code git repo /home/me/source/quarkus, ref 3.27.5.1, module core}
     *         or the path of a Maven sources jar
     */
    String origin();

    /**
     * @param fqcn fully qualified class name
     * @return the content of the source file, or an empty optional if the class
     *         has no source file in this artifact
     */
    Optional<String> read(String fqcn);

    /**
     * @return the fully qualified names of all main source files
     *         ({@code src/main/java}) available in this artifact
     */
    List<String> listFqcns();

    /**
     * @return {@code true} if the sources exactly match the requested version,
     *         e.g. a git tag or a sources jar of that version; {@code false} for
     *         approximations, e.g. a release version read from a maintenance
     *         branch - the resolver chain then prefers an exact match of a
     *         later resolver
     */
    default boolean exact() {
        return true;
    }

    /**
     * @return warnings about the located sources, reported to the client
     *         along with the tool result (e.g. why the sources are not an
     *         exact match); empty by default
     */
    default List<String> warnings() {
        return List.of();
    }
}
