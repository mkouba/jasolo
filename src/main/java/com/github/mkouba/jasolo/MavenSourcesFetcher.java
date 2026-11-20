package com.github.mkouba.jasolo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.smallrye.common.process.AbnormalExitException;
import jakarta.inject.Singleton;

/**
 * Fetches the {@code :sources} jar of an artifact on demand, via the
 * equivalent of
 * {@code mvn dependency:get -Dartifact=g:a:v:jar:sources -Dtransitive=false},
 * when no {@link SourceResolver} was able to locate the sources locally
 * (see {@code jasolo.maven.fetch.enabled}).
 * <p>
 * The executed command is the configured {@code jasolo.maven.fetch.command}
 * (a list, so arguments are expressible) followed by the fixed arguments
 * {@code -B -ntp dependency:get -Dartifact=<g:a:v>:jar:sources
 * -Dtransitive=false -Dmaven.repo.local=<jasolo.maven.local-repo>}.
 * <p>
 * The user's Maven settings (mirrors, proxies, TLS trust) are honoured because
 * the configured Maven command does the download. Failed fetches are
 * remembered per GAV (bounded LRU), so that repeated tool calls for the same
 * missing artifact do not re-run Maven every time.
 */
@Singleton
public class MavenSourcesFetcher {

    private static final Logger LOG = Logger.getLogger(MavenSourcesFetcher.class);

    /**
     * Safety limit for failed GAVs remembered to avoid re-running Maven
     * (access-ordered LRU).
     */
    private static final int MAX_FAILED_GAVS = 256;

    /**
     * Safety limit for the Maven error output carried in a failure detail.
     */
    private static final int MAX_DETAIL_CHARS = 1000;

    private final boolean enabled;
    private final List<String> command;
    private final Path localRepoPath;
    // access-ordered: gav -> failure detail of the last fetch attempt
    private final Map<String, String> failedGavs = new LinkedHashMap<>(16, 0.75f, true);

    MavenSourcesFetcher(JasoloConfig config) {
        this.enabled = config.maven().fetch().enabled();
        this.command = List.copyOf(config.maven().fetch().command());
        this.localRepoPath = Path.of(config.maven().localRepo());
    }

    boolean enabled() {
        return enabled;
    }

    /**
     * The outcome of an on-demand fetch attempt.
     *
     * @param fetched whether the sources jar is now present in the local
     *        Maven repository
     * @param detail when {@code fetched} is {@code false}, a human-readable
     *        explanation of the failure
     */
    record FetchResult(boolean fetched, String detail) {

        static FetchResult success() {
            return new FetchResult(true, null);
        }

        static FetchResult failure(String detail) {
            return new FetchResult(false, detail);
        }
    }

    /**
     * @param gav the GAV whose {@code :sources} jar should be downloaded
     * @return the outcome of the fetch attempt
     */
    FetchResult fetch(Gav gav) {
        String key = gav.toString();
        synchronized (failedGavs) {
            String previous = failedGavs.get(key);
            if (previous != null) {
                LOG.debugf("Not fetching %s again, the previous attempt failed: %s", gav, previous);
                return FetchResult.failure(previous);
            }
        }
        FetchResult result = doFetch(gav);
        if (!result.fetched()) {
            synchronized (failedGavs) {
                failedGavs.put(key, result.detail());
                while (failedGavs.size() > MAX_FAILED_GAVS) {
                    Iterator<String> eldest = failedGavs.keySet().iterator();
                    LOG.debugf("Failed-GAV cache full (%d entries), evicted: %s", MAX_FAILED_GAVS, eldest.next());
                    eldest.remove();
                }
            }
        }
        return result;
    }

    /**
     * @param gav the GAV of a previous fetch attempt
     * @return the explanation of the last failed fetch attempt, or an empty
     *         optional if there was none
     */
    Optional<String> failureFor(Gav gav) {
        synchronized (failedGavs) {
            return Optional.ofNullable(failedGavs.get(gav.toString()));
        }
    }

    private FetchResult doFetch(Gav gav) {
        String artifact = gav.groupId() + ":" + gav.artifactId() + ":" + gav.version() + ":jar:sources";
        List<String> command = new ArrayList<>(this.command);
        command.addAll(List.of("-B", "-ntp", "dependency:get",
                "-Dartifact=" + artifact, "-Dtransitive=false",
                "-Dmaven.repo.local=" + localRepoPath));
        LOG.infof("Fetching the sources jar of %s on demand: %s", gav, String.join(" ", command));
        try {
            Processes.exec(null, command);
        } catch (AbnormalExitException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("Could not find artifact")) {
                if (Files.isRegularFile(artifactJar(gav))) {
                    return FetchResult.failure("the artifact " + gav
                            + " exists in the local Maven repository but no sources jar is published for it"
                            + " - falling back to decompiling may be the only option");
                }
                return FetchResult.failure("no artifact with coordinates " + gav
                        + " (and no sources jar) was found in the configured remote repositories");
            }
            return FetchResult.failure("the Maven command failed: " + truncate(message));
        } catch (RuntimeException e) {
            // e.g. the configured command does not exist or is not on the PATH
            // of the server process (MCP clients often do not launch jasolo
            // from a shell)
            LOG.errorf("Execution of the Maven command failed: %s", e.getMessage());
            return FetchResult.failure("unable to execute '" + String.join(" ", this.command)
                    + "' (is it installed and on the PATH of the jasolo process?): " + e.getMessage()
                    + ". Install Maven, or set jasolo.maven.fetch.command to the absolute path of a"
                    + " Maven executable - mvn, mvnd or an IDE-bundled Maven all work");
        }
        Path sourcesJar = sourcesJar(gav);
        if (!Files.isRegularFile(sourcesJar)) {
            return FetchResult.failure("Maven reported success but the sources jar was not found at "
                    + sourcesJar);
        }
        return FetchResult.success();
    }

    private Path artifactJar(Gav gav) {
        return artifactDir(gav).resolve(gav.artifactId() + "-" + gav.version() + ".jar");
    }

    private Path sourcesJar(Gav gav) {
        return artifactDir(gav).resolve(gav.artifactId() + "-" + gav.version() + "-sources.jar");
    }

    private Path artifactDir(Gav gav) {
        return localRepoPath
                .resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(gav.version());
    }

    private static String truncate(String message) {
        String trimmed = message.strip();
        return trimmed.length() <= MAX_DETAIL_CHARS ? trimmed
                : trimmed.substring(0, MAX_DETAIL_CHARS) + "...";
    }
}
