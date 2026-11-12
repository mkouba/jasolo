package com.github.mkouba.jasolo;

import java.util.Optional;

/**
 * Locates the sources of an artifact.
 * <p>
 * Implementations are CDI beans, ordered by {@link jakarta.annotation.Priority}
 * (higher value = higher precedence). If a resolver cannot handle a GAV it must
 * return an empty optional so that the next resolver in the chain gets a chance.
 */
public interface SourceResolver {

    /**
     * @return the resolver id, used in error messages and diagnostics
     */
    String name();

    /**
     * @param gav the GAV to locate
     * @return the located sources, or an empty optional if this resolver
     *         cannot handle the GAV
     */
    Optional<LocatedSources> locate(Gav gav);
}
