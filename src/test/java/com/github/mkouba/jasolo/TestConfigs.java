package com.github.mkouba.jasolo;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Plain implementation of {@link JasoloConfig} for unit tests.
 */
final class TestConfigs {

    private TestConfigs() {
    }

    static JasoloConfig of(Path gitRoot, Path m2Repo) {
        return of(gitRoot, m2Repo, "show", 16, 256);
    }

    static JasoloConfig of(Path gitRoot, Path m2Repo, int maxJars, int maxFiles) {
        return of(gitRoot, m2Repo, "show", maxJars, maxFiles, List.of());
    }

    static JasoloConfig of(Path gitRoot, Path m2Repo, List<String> cacheExclusions) {
        return of(gitRoot, m2Repo, "show", 16, 256, cacheExclusions, List.of());
    }

    static JasoloConfig of(Path gitRoot, Path m2Repo, String accessMode) {
        return of(gitRoot, m2Repo, accessMode, 16, 256, List.of(), List.of());
    }

    static JasoloConfig of(Path gitRoot, Path m2Repo, String accessMode, int maxJars, int maxFiles) {
        return of(gitRoot, m2Repo, accessMode, maxJars, maxFiles, List.of(), List.of());
    }

    static JasoloConfig of(Path gitRoot, Path m2Repo, String accessMode, int maxJars, int maxFiles,
            List<String> cacheExclusions) {
        return of(gitRoot, m2Repo, accessMode, maxJars, maxFiles, cacheExclusions, List.of());
    }

    static JasoloConfig of(Path gitRoot, Path m2Repo, String accessMode, int maxJars, int maxFiles,
            List<String> cacheExclusions, List<String> gitExclusions) {
        List<String> roots = gitRoot == null ? List.of() : List.of(gitRoot.toString());
        String localRepo = m2Repo == null ? "/nonexistent-m2" : m2Repo.toString();
        return new JasoloConfig() {

            @Override
            public Git git() {
                return new Git() {

                    @Override
                    public Optional<List<String>> roots() {
                        return Optional.of(roots);
                    }

                    @Override
                    public Optional<List<String>> exclude() {
                        return Optional.of(gitExclusions);
                    }

                    @Override
                    public String accessMode() {
                        return accessMode;
                    }

                    @Override
                    public List<String> refPatterns() {
                        return List.of("{version}", "v{version}", "{major}.{minor}", "{major}.{minor}.x",
                                "development/{major}.{minor}");
                    }
                };
            }

            @Override
            public Maven maven() {
                return new Maven() {

                    @Override
                    public String localRepo() {
                        return localRepo;
                    }

                    @Override
                    public Cache cache() {
                        return new Cache() {

                            @Override
                            public int maxJars() {
                                return maxJars;
                            }

                            @Override
                            public int maxFiles() {
                                return maxFiles;
                            }

                            @Override
                            public Optional<List<String>> exclude() {
                                return Optional.of(cacheExclusions);
                            }
                        };
                    }
                };
            }
        };
    }
}
