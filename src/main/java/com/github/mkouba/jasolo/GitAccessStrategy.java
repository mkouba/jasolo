package com.github.mkouba.jasolo;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Strategy for reading files from a specific git ref of a local repository.
 * <p>
 * Implementations are CDI beans selected via {@code jasolo.git.access-mode}.
 */
public interface GitAccessStrategy {

    /**
     * @return the strategy id, matched against {@code jasolo.git.access-mode}
     */
    String id();

    /**
     * Validates that the prerequisites of this strategy are met. Invoked once
     * at application startup when the git resolver is active (git roots
     * configured and this strategy selected); a failing validation prevents
     * the application from starting. The default implementation does nothing.
     */
    default void validate() {
    }

    /**
     * @param repoDir the git repository directory
     * @param ref a ref name resolvable in the repository (tag, branch, remote branch)
     * @param repoRelativePath repository-relative path with forward slashes,
     *        e.g. {@code core/runtime/src/main/java/io/quarkus/Quarkus.java}
     * @return the file content, or an empty optional if the file does not exist on the ref
     */
    Optional<String> readFile(Path repoDir, String ref, String repoRelativePath);

    /**
     * @param repoDir the git repository directory
     * @param ref a ref name resolvable in the repository
     * @param repoRelativeDir repository-relative directory with forward slashes
     * @return the repository-relative paths of all files under the directory on the ref
     */
    List<String> listFiles(Path repoDir, String ref, String repoRelativeDir);
}
