package com.github.mkouba.jasolo;

import java.util.List;

/**
 * Thrown when no resolver in the chain was able to locate the sources of an artifact.
 */
@SuppressWarnings("serial")
public class SourceNotFoundException extends RuntimeException {

    private final Gav gav;
    private final List<String> tried;

    public SourceNotFoundException(Gav gav, List<String> tried) {
        super(buildMessage(gav, tried));
        this.gav = gav;
        this.tried = List.copyOf(tried);
    }

    public Gav gav() {
        return gav;
    }

    public List<String> tried() {
        return tried;
    }

    private static String buildMessage(Gav gav, List<String> tried) {
        return "No source resolver was able to locate the sources of " + gav
                + ". Tried resolvers: " + String.join(", ", tried)
                + ". Hint: make sure the artifact's sources jar exists in the local Maven repository"
                + " or that a matching git repo is cloned under one of the configured jasolo.git.roots.";
    }
}
