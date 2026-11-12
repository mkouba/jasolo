package com.github.mkouba.jasolo;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Non-destructive git access: reads file content directly from a ref via
 * {@code git show <ref>:<path>} - the working copy is never touched, no
 * checkout is performed.
 */
@ApplicationScoped
public class ShowGitAccessStrategy implements GitAccessStrategy {

    private static final String GIT = "git";

    @Override
    public String id() {
        return "show";
    }

    @Override
    public void validate() {
        try {
            // exits with 0 and prints the version when the CLI is usable
            Processes.exec(null, List.of(GIT, "--version"));
        } catch (RuntimeException e) {
            throw new IllegalStateException("The 'show' git access strategy requires the git CLI,"
                    + " but 'git --version' failed - install git or configure a different"
                    + " jasolo.git.access-mode", e);
        }
    }

    @Override
    public Optional<String> readFile(Path repoDir, String ref, String repoRelativePath) {
        return Processes.tryExec(repoDir, List.of(GIT, "show", ref + ":" + repoRelativePath));
    }

    @Override
    public List<String> listFiles(Path repoDir, String ref, String repoRelativeDir) {
        String out = Processes.exec(repoDir,
                List.of(GIT, "ls-tree", "-r", "--name-only", ref, "--", repoRelativeDir));
        return Arrays.stream(out.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
