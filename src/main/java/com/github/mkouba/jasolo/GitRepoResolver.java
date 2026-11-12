package com.github.mkouba.jasolo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Resolves the sources of a GAV from a local git clone: the GA must be indexed
 * (see {@link GitRepoIndex}) and the GAV version must map to an existing ref.
 * <p>
 * The bean is eagerly initialized at startup and validates its configuration
 * in the constructor, so a misconfigured application fails to start.
 */
@Startup
@Singleton
@Priority(200)
public class GitRepoResolver implements SourceResolver {

    private static final Logger LOG = Logger.getLogger(GitRepoResolver.class);

    private static final Pattern MAJOR_MINOR_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)");

    private static final String TAGS_PREFIX = "refs/tags/";
    private static final String HEADS_PREFIX = "refs/heads/";
    private static final String REMOTES_PREFIX = "refs/remotes/";

    private final JasoloConfig config;
    private final GitRepoIndex index;
    private final GitAccessStrategy strategy;
    // ref sets cached per repository, see resolveRef()
    private final ConcurrentMap<Path, Refs> refsCache = new ConcurrentHashMap<>();

    @Inject
    GitRepoResolver(JasoloConfig config, Instance<GitAccessStrategy> strategies) {
        this(config, new GitRepoIndex(config), selectStrategy(config, strategies));
    }

    GitRepoResolver(JasoloConfig config, GitRepoIndex index, List<GitAccessStrategy> strategies) {
        this(config, index, selectStrategy(config, strategies));
    }

    private GitRepoResolver(JasoloConfig config, GitRepoIndex index, GitAccessStrategy strategy) {
        this.config = config;
        this.index = index;
        this.strategy = strategy;
        validate(config, strategy);
    }

    @Override
    public String name() {
        return "git-repo";
    }

    @Override
    public Optional<LocatedSources> locate(Gav gav) {
        GitRepoIndex.ModuleLocation module = index.index().get(gav.ga());
        if (module == null) {
            LOG.debugf("GA %s is not produced by any indexed git repo", gav.ga());
            return Optional.empty();
        }
        ResolvedRef ref = resolveRef(module.repoDir(), gav);
        if (ref == null) {
            LOG.debugf("No git ref found for version %s in %s", gav.version(), module.repoDir());
            return Optional.empty();
        }
        List<String> warnings = List.of();
        if (!ref.exact()) {
            warnings = List.of("No tag matches version " + gav.version() + " in " + module.repoDir()
                    + "; the sources were read from branch " + ref.name()
                    + " and may differ from the released version. Run 'git fetch --tags' in the repository"
                    + " if the tag should exist.");
            LOG.warnf("%s located in %s at branch %s - no matching tag", gav, module.repoDir(), ref.name());
        } else {
            LOG.infof("%s located in %s at ref %s", gav, module.repoDir(), ref.name());
        }
        return Optional.of(new GitSources(module, ref, strategy, warnings));
    }

    /**
     * Selects the git access strategy matching the configured access mode.
     */
    private static GitAccessStrategy selectStrategy(JasoloConfig config, Iterable<GitAccessStrategy> strategies) {
        String accessMode = config.git().accessMode();
        for (GitAccessStrategy candidate : strategies) {
            if (candidate.id().equals(accessMode)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No git access strategy with id '" + accessMode + "' found");
    }

    /**
     * Fail-fast validation, invoked at application startup (the bean is
     * eagerly initialized): every configured git root must exist on disk,
     * and the selected git access strategy must meet its prerequisites.
     * <p>
     * Skipped when no git roots are configured - the git resolver is disabled
     * then (m2-only mode) and neither the roots nor the strategy are used.
     */
    private static void validate(JasoloConfig config, GitAccessStrategy strategy) {
        List<Path> rootDirs = effectiveRoots(config);
        for (Path rootDir : rootDirs) {
            if (!Files.isDirectory(rootDir)) {
                throw new IllegalStateException("Configured git root does not exist: " + rootDir
                        + " - fix the path or remove it from jasolo.git.roots");
            }
        }
        if (!rootDirs.isEmpty()) {
            strategy.validate();
        }
    }

    /**
     * @return the effective git roots: blank entries are skipped and paths
     *         are normalized - the same roots the {@link GitRepoIndex} scans
     */
    private static List<Path> effectiveRoots(JasoloConfig config) {
        return config.git().roots().orElseGet(List::of).stream()
                .filter(root -> root != null && !root.isBlank())
                .map(root -> Path.of(root.trim()).toAbsolutePath().normalize())
                .toList();
    }

    /**
     * A ref a version was mapped to.
     *
     * @param name the short ref name, e.g. {@code 3.27.5.1} or {@code origin/3.27}
     * @param exact whether the ref is an exact match for the version - a tag, or
     *        the default branch for a snapshot version; a release version
     *        mapped to a (moving) branch is not exact
     */
    record ResolvedRef(String name, boolean exact) {
    }

    /**
     * The refs of a repository.
     *
     * @param tags short tag names
     * @param branches short local and remote branch names, e.g. {@code main} and {@code origin/main}
     */
    record Refs(Set<String> tags, Set<String> branches) {
    }

    /**
     * Maps a version to an existing ref of the repository, or returns {@code null}.
     * Snapshot versions map to the default branch; release versions are matched
     * against the configured ref patterns.
     * <p>
     * The set of refs is cached per repository; when no ref matches, or a
     * release version only matches a branch, the cache is refreshed once, so
     * refs gained in the meantime (e.g. a tag after {@code git fetch --tags})
     * are picked up without a restart.
     */
    ResolvedRef resolveRef(Path repoDir, Gav gav) {
        Refs refs = refsCache.computeIfAbsent(repoDir, this::listRefs);
        LOG.debugf("Resolving ref for %s in %s: refs=%s", gav, repoDir, refs);
        ResolvedRef ref = matchRef(refs, gav);
        if (ref == null || !ref.exact()) {
            LOG.debugf("No exact ref of %s matches, refreshing the cached ref set", repoDir);
            refsCache.put(repoDir, refs = listRefs(repoDir));
            ref = matchRef(refs, gav);
        }
        return ref;
    }

    private ResolvedRef matchRef(Refs refs, Gav gav) {
        if (gav.isSnapshot()) {
            // local and remote default branch candidates
            for (String candidate : List.of("main", "origin/main", "master", "origin/master")) {
                if (refs.branches().contains(candidate)) {
                    return new ResolvedRef(candidate, true);
                }
            }
            return null;
        }
        Matcher matcher = MAJOR_MINOR_PATTERN.matcher(gav.version());
        String major = null;
        String minor = null;
        if (matcher.find()) {
            major = matcher.group(1);
            minor = matcher.group(2);
        }
        for (String pattern : config.git().refPatterns()) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (major == null) {
                // no major.minor in the version - only try patterns without placeholders
                if (pattern.contains("{major}") || pattern.contains("{minor}")) {
                    continue;
                }
            }
            String ref = pattern.replace("{version}", gav.version())
                    .replace("{major}", major == null ? "" : major)
                    .replace("{minor}", minor == null ? "" : minor);
            if (refs.tags().contains(ref)) {
                return new ResolvedRef(ref, true);
            }
            if (refs.branches().contains(ref)) {
                return new ResolvedRef(ref, false);
            }
            if (refs.branches().contains("origin/" + ref)) {
                return new ResolvedRef("origin/" + ref, false);
            }
        }
        return null;
    }

    private Refs listRefs(Path repoDir) {
        String out = Processes.exec(repoDir, List.of("git", "for-each-ref",
                "--format=%(refname)", "refs/heads", "refs/tags", "refs/remotes"));
        Set<String> tags = new HashSet<>();
        Set<String> branches = new HashSet<>();
        Stream.of(out.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(refName -> {
                    if (refName.startsWith(TAGS_PREFIX)) {
                        tags.add(refName.substring(TAGS_PREFIX.length()));
                    } else if (refName.startsWith(HEADS_PREFIX)) {
                        branches.add(refName.substring(HEADS_PREFIX.length()));
                    } else if (refName.startsWith(REMOTES_PREFIX)) {
                        branches.add(refName.substring(REMOTES_PREFIX.length()));
                    }
                });
        return new Refs(Set.copyOf(tags), Set.copyOf(branches));
    }

    static class GitSources implements LocatedSources {

        private final GitRepoIndex.ModuleLocation module;
        private final String ref;
        private final boolean exact;
        private final GitAccessStrategy strategy;
        private final List<String> warnings;

        GitSources(GitRepoIndex.ModuleLocation module, ResolvedRef ref, GitAccessStrategy strategy,
                List<String> warnings) {
            this.module = module;
            this.ref = ref.name();
            this.exact = ref.exact();
            this.strategy = strategy;
            this.warnings = warnings;
        }

        @Override
        public boolean exact() {
            return exact;
        }

        @Override
        public List<String> warnings() {
            return warnings;
        }

        @Override
        public String origin() {
            String moduleInfo = module.moduleDir().isEmpty() ? "" : ", module " + module.moduleDir();
            return "git repo " + module.repoDir() + moduleInfo + ", ref " + ref;
        }

        @Override
        public Optional<String> read(String fqcn) {
            for (String srcRoot : List.of("src/main/java", "src/test/java")) {
                String path = repoRelativePath(srcRoot, fqcn);
                Optional<String> content = strategy.readFile(module.repoDir(), ref, path);
                if (content.isPresent()) {
                    LOG.debugf("Read %s from %s at ref %s", path, module.repoDir(), ref);
                    return content;
                }
                LOG.debugf("No file %s at ref %s in %s", path, ref, module.repoDir());
            }
            return Optional.empty();
        }

        @Override
        public List<String> listFqcns() {
            String srcDir = moduleDirPrefix() + "src/main/java";
            List<String> fqcns = strategy.listFiles(module.repoDir(), ref, srcDir).stream()
                    .filter(path -> path.startsWith(srcDir) && path.endsWith(".java"))
                    .map(path -> path.substring(srcDir.length() + 1, path.length() - ".java".length())
                            .replace('/', '.'))
                    .toList();
            LOG.debugf("Listed %d classes in %s at ref %s", fqcns.size(), module.repoDir(), ref);
            return fqcns;
        }

        private String repoRelativePath(String srcRoot, String fqcn) {
            return moduleDirPrefix() + srcRoot + "/" + fqcn.replace('.', '/') + ".java";
        }

        private String moduleDirPrefix() {
            return module.moduleDir().isEmpty() ? "" : module.moduleDir() + "/";
        }
    }
}
