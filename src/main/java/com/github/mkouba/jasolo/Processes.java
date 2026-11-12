package com.github.mkouba.jasolo;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.smallrye.common.process.AbnormalExitException;
import io.smallrye.common.process.ProcessBuilder;

/**
 * Minimal one-shot process execution helpers on top of
 * {@code io.smallrye.common:smallrye-common-process}, which drains stdout and
 * stderr concurrently (no pipe deadlocks) and supports exit timeouts.
 */
final class Processes {

    private static final Logger LOG = Logger.getLogger(Processes.class);

    /**
     * Safety limit for captured output (16M chars) - much larger than any
     * source file we ever want to read.
     */
    private static final int MAX_CHARS = 16 * 1024 * 1024;

    private Processes() {
    }

    /**
     * Runs a command and returns its standard output.
     *
     * @param workingDir the working directory, or {@code null} for the current one
     * @param command the command to run; the first element is the executable
     * @return the standard output of the command
     * @throws AbnormalExitException if the command exits with a non-zero code
     *         (the gathered stderr is part of the exception message)
     */
    static String exec(Path workingDir, List<String> command) {
        String description = String.join(" ", command);
        long start = System.nanoTime();
        LOG.debugf("Executing [%s] in %s", description, workingDir == null ? "." : workingDir);
        try {
            ProcessBuilder<Void> builder = ProcessBuilder.newBuilder(command.get(0))
                    .arguments(command.subList(1, command.size()));
            if (workingDir != null) {
                builder = builder.directory(workingDir);
            }
            String output = builder
                    .error().gatherOnFail(true)
                    .output().toSingleString(MAX_CHARS)
                    .run();
            LOG.debugf("Executed [%s] in %d ms", description, (System.nanoTime() - start) / 1_000_000);
            return output;
        } catch (AbnormalExitException e) {
            // expected failure (non-zero exit) - the message carries the gathered stderr,
            // the stack trace would be noise
            LOG.errorf("Execution of [%s] failed in %d ms: %s", description,
                    (System.nanoTime() - start) / 1_000_000, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            LOG.errorf(e, "Execution of [%s] failed in %d ms", description,
                    (System.nanoTime() - start) / 1_000_000);
            throw e;
        }
    }

    /**
     * Runs a command and returns its standard output, or an empty optional
     * if the command exits with a non-zero code.
     */
    static Optional<String> tryExec(Path workingDir, List<String> command) {
        try {
            return Optional.of(exec(workingDir, command));
        } catch (AbnormalExitException e) {
            return Optional.empty();
        }
    }
}
