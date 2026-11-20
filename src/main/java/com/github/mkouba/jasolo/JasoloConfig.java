package com.github.mkouba.jasolo;

import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "jasolo")
public interface JasoloConfig {

    /**
     * Git repo related configuration.
     */
    Git git();

    /**
     * Local Maven repository related configuration.
     */
    Maven maven();

    interface Git {

        /**
         * @return directories scanned recursively for cloned git repos,
         *         e.g. {@code /home/me/source}; a discovered repo is not
         *         descended into and a root may itself be a repo; empty
         *         disables the git resolver (m2-only mode)
         */
        Optional<List<String>> roots();

        /**
         * @return paths relative to a git root whose subtrees are skipped
         *         during the recursive repo scan and never indexed as
         *         repositories, e.g. {@code advisories} or
         *         {@code experiments/some-checkout}
         */
        Optional<List<String>> exclude();

        /**
         * @return the id of the {@link GitAccessStrategy} used to read files from a git ref
         *         ({@code show} by default - non-destructive reads via {@code git show})
         */
        @WithDefault("show")
        String accessMode();

        /**
         * @return ordered ref patterns tried when mapping a GAV version to a git ref;
         *         supported placeholders: {@code {version}}, {@code {major}}, {@code {minor}}
         */
        @WithDefault("{version},v{version},{major}.{minor},{major}.{minor}.x,development/{major}.{minor}")
        List<String> refPatterns();
    }

    interface Maven {

        /**
         * @return path of the local Maven repository
         */
        @WithDefault("${user.home}/.m2/repository")
        String localRepo();

        /**
         * Fetching of missing sources jars on demand.
         */
        Fetch fetch();

        /**
         * Caching of located sources jars.
         */
        Cache cache();

        interface Fetch {

            /**
             * @return {@code true} if the {@code :sources} jar of an artifact
             *         is downloaded on demand (via
             *         {@code mvn dependency:get}) when no resolver can locate
             *         the sources locally - the user's Maven settings
             *         (mirrors, proxies, TLS trust) are honoured
             */
            @WithDefault("false")
            boolean enabled();

            /**
             * @return the Maven command used for the on-demand fetch - the first
             *         element is the executable, further elements are leading
             *         arguments; jasolo appends
             *         {@code -B -ntp dependency:get -Dartifact=g:a:v:jar:sources -Dtransitive=false -Dmaven.repo.local=<local-repo>}.
             *         e.g. {@code mvn} (default), {@code mvnd} (faster
             *         startup), a wrapper script or the absolute path of an
             *         IDE-bundled Maven; configured as a comma-separated list,
             *         e.g. {@code jasolo.maven.fetch.command=mvnd,--mode=daemon}
             */
            @WithDefault("mvn")
            List<String> command();
        }

        interface Cache {

            /**
             * @return the maximum number of located sources jars kept in the
             *         cache (LRU); {@code 0} disables caching entirely
             */
            @WithDefault("16")
            int maxJars();

            /**
             * @return the maximum number of source file contents cached per
             *         located jar (LRU); {@code 0} disables content caching -
             *         the fqcn listing is still cached per located jar
             */
            @WithDefault("256")
            int maxFiles();

            /**
             * @return {@code groupId:artifactId} coordinates whose sources jars
             *         are never cached, e.g. known large jars - every read of
             *         an excluded artifact goes to disk
             */
            Optional<List<String>> exclude();
        }
    }
}
