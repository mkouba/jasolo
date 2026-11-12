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
         * @return directories whose immediate children are cloned git repos,
         *         e.g. {@code /home/me/source} or {@code /home/me/source/quarkiverse};
         *         empty disables the git resolver (m2-only mode)
         */
        Optional<List<String>> roots();

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
         * Caching of located sources jars.
         */
        Cache cache();

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
