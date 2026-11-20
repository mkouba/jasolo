package com.github.mkouba.jasolo;

import java.util.List;

/**
 * Thrown when no resolver in the chain was able to locate the sources of an artifact.
 */
@SuppressWarnings("serial")
public class SourceNotFoundException extends RuntimeException {

    private final Gav gav;
    private final List<String> tried;
    private final String fetchFailure;

    public SourceNotFoundException(Gav gav, List<String> tried) {
        this(gav, tried, null);
    }

    /**
     * @param gav the GAV that could not be located
     * @param tried the names of the resolvers in the chain
     * @param fetchFailure if an on-demand fetch of the sources jar was
     *        attempted, the explanation of its failure; {@code null} if no
     *        fetch was attempted
     */
    public SourceNotFoundException(Gav gav, List<String> tried, String fetchFailure) {
        super(buildMessage(gav, tried, fetchFailure));
        this.gav = gav;
        this.tried = List.copyOf(tried);
        this.fetchFailure = fetchFailure;
    }

    public Gav gav() {
        return gav;
    }

    public List<String> tried() {
        return tried;
    }

    /**
     * @return the explanation of a failed on-demand fetch, or {@code null}
     *         if no fetch was attempted
     */
    public String fetchFailure() {
        return fetchFailure;
    }

    private static String buildMessage(Gav gav, List<String> tried, String fetchFailure) {
        StringBuilder message = new StringBuilder("No source resolver was able to locate the sources of ")
                .append(gav).append(". Tried resolvers: ").append(String.join(", ", tried)).append(".");
        if (fetchFailure != null) {
            message.append(" An on-demand fetch of the sources jar failed: ").append(fetchFailure).append(".");
        } else {
            message.append(" Hint: make sure the artifact's sources jar exists in the local Maven repository")
                    .append(" or that a matching git repo is cloned under one of the configured jasolo.git.roots.")
                    .append(" You can also download the sources jar yourself with")
                    .append(" 'mvn -B dependency:get -Dartifact=").append(gav).append(":jar:sources")
                    .append(" -Dtransitive=false'")
                    .append(" or set jasolo.maven.fetch.enabled=true to let jasolo fetch it on demand.");
        }
        return message.toString();
    }
}
