package com.github.mkouba.jasolo;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final Set<String> excludedPaths;

    private volatile Map<String, ModuleLocation> index;

    GitRepoIndex(JasoloConfig config) {
        this.gitRoots = config.git().roots().orElseGet(List::of);
        this.excludedPaths = config.git().exclude().orElseGet(List::of)
                .stream()
                .map(String::trim)
                .map(path -> path.replaceAll("^/+", "").replaceAll("/+$", ""))
                .filter(path -> !path.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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
        // Repos are discovered recursively; a discovered repo is not descended
        // into - its nested repositories (submodules, quickstarts, worktrees)
        // belong to the repo, not to the configured roots. The root itself may
        // be a repo.
        List<Path> repoDirs = new ArrayList<>();
        try {
            Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // the root itself is always scanned, excluded paths are not;
                    // the walk is top-down, so an exact match of the
                    // root-relative path is sufficient
                    if (!dir.equals(rootDir)) {
                        String relative = rootDir.relativize(dir).toString().replace('\\', '/');
                        if (excludedPaths.contains(relative)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    // .git is a directory for a normal clone and a file for
                    // worktrees/submodules - both are git repositories
                    if (Files.exists(dir.resolve(".git"))) {
                        repoDirs.add(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    LOG.warnf(e, "Unable to scan %s", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warnf(e, "Unable to scan git root %s", rootDir);
        }
        // Deterministic order - when an artifact is produced by multiple
        // repos, the repo closest to the root wins: primary clones tend to
        // sit directly under a root, secondary copies (advisory checkouts,
        // experiments) are nested deeper; ties are broken lexicographically
        repoDirs.sort(Comparator
                .comparingInt((Path dir) -> rootDir.relativize(dir).getNameCount())
                .thenComparing(Comparator.naturalOrder()));
        repoDirs.forEach(repoDir -> scanRepo(repoDir, result));
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
