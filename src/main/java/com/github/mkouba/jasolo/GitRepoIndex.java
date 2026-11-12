package com.github.mkouba.jasolo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

/**
 * Maps {@code groupId:artifactId} to the git repository and module directory
 * that produces the artifact. Built lazily by scanning the pom files of all
 * git repos found under the configured {@code jasolo.git.roots}.
 * <p>
 * Not a CDI bean - it is an internal helper owned by {@link GitRepoResolver}.
 */
final class GitRepoIndex {

    private static final Logger LOG = Logger.getLogger(GitRepoIndex.class);

    /**
     * @param repoDir the git repository directory
     * @param moduleDir repository-relative module directory with forward slashes,
     *        empty string for the root module
     */
    record ModuleLocation(Path repoDir, String moduleDir) {
    }

    private final List<String> gitRoots;

    private volatile Map<String, ModuleLocation> index;

    GitRepoIndex(JasoloConfig config) {
        this.gitRoots = config.git().roots().orElseGet(List::of);
    }

    /**
     * @return the lazily built index: {@code groupId:artifactId -> module location}
     */
    Map<String, ModuleLocation> index() {
        Map<String, ModuleLocation> result = index;
        if (result == null) {
            synchronized (this) {
                result = index;
                if (result == null) {
                    result = build();
                    index = result;
                }
            }
        }
        return result;
    }

    void invalidate() {
        index = null;
    }

    private Map<String, ModuleLocation> build() {
        long start = System.nanoTime();
        Map<String, ModuleLocation> result = new HashMap<>();
        for (String root : gitRoots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path rootDir = Path.of(root.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(rootDir)) {
                LOG.warnf("Configured git root does not exist: %s", rootDir);
                continue;
            }
            scanRoot(rootDir, result);
        }
        LOG.infof("Git repo index built in %d ms: %d artifacts found",
                (System.nanoTime() - start) / 1_000_000, result.size());
        return Map.copyOf(result);
    }

    private void scanRoot(Path rootDir, Map<String, ModuleLocation> result) {
        try (Stream<Path> children = Files.list(rootDir)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> Files.isDirectory(dir.resolve(".git")))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(repoDir -> scanRepo(repoDir, result));
        } catch (IOException e) {
            LOG.warnf(e, "Unable to scan git root %s", rootDir);
        }
    }

    private void scanRepo(Path repoDir, Map<String, ModuleLocation> result) {
        Path rootPom = repoDir.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            scanModule(repoDir, "", rootPom, result);
        } else {
            // Repos without a root pom: look for pom files two levels deep
            try (Stream<Path> poms = Files.walk(repoDir, 2)) {
                poms.filter(path -> path.getFileName() != null
                        && path.getFileName().toString().equals("pom.xml"))
                        .filter(Files::isRegularFile)
                        .forEach(pom -> {
                            String moduleDir = relativeModuleDir(repoDir, pom.getParent());
                            if (moduleDir != null) {
                                scanModule(repoDir, moduleDir, pom, result);
                            }
                        });
            } catch (IOException e) {
                LOG.warnf(e, "Unable to walk repo %s", repoDir);
            }
        }
    }

    private void scanModule(Path repoDir, String moduleDir, Path pomPath, Map<String, ModuleLocation> result) {
        PomInfo pom;
        try {
            pom = PomInfo.parse(pomPath);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Skipping unparsable pom %s", pomPath);
            return;
        }
        if (pom.groupId != null && !"pom".equals(pom.packaging)) {
            String ga = pom.groupId + ":" + pom.artifactId;
            ModuleLocation existing = result.get(ga);
            if (existing == null) {
                result.put(ga, new ModuleLocation(repoDir, moduleDir));
            } else if (!existing.repoDir().equals(repoDir)) {
                LOG.warnf("Artifact %s is produced by multiple repos, keeping %s (also found in %s)",
                        ga, existing.repoDir(), repoDir);
            }
        }
        for (String module : pom.modules) {
            Path moduleDirPath = pomPath.getParent().resolve(module).normalize();
            Path modulePom = moduleDirPath.resolve("pom.xml");
            String childModuleDir = relativeModuleDir(repoDir, moduleDirPath);
            if (childModuleDir != null && Files.isRegularFile(modulePom)) {
                scanModule(repoDir, childModuleDir, modulePom, result);
            }
        }
    }

    private static String relativeModuleDir(Path repoDir, Path dir) {
        Path relative = repoDir.relativize(dir.normalize());
        String moduleDir = relative.toString().replace('\\', '/');
        if (moduleDir.startsWith("../")) {
            // module outside of the repo (unusual) - do not index
            return null;
        }
        return moduleDir;
    }
}
