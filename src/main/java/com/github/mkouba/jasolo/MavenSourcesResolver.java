package com.github.mkouba.jasolo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

/**
 * Resolves the sources of a GAV from the {@code <artifactId>-<version>-sources.jar}
 * in the local Maven repository.
 * <p>
 * Located jars are cached in a bounded LRU ({@code jasolo.maven.cache.max-jars});
 * an entry is dropped when the jar file changes on disk (modification time and
 * size are checked whenever a jar is located). Each cached jar also caches its
 * fqcn listing and up to {@code jasolo.maven.cache.max-files} file contents.
 * <p>
 * The bean is eagerly initialized at startup and validates its configuration
 * in the constructor, so a misconfigured application fails to start.
 */
@Startup
@Singleton
@Priority(100)
public class MavenSourcesResolver implements SourceResolver {

    private static final Logger LOG = Logger.getLogger(MavenSourcesResolver.class);

    private final Path localRepoPath;
    // groupId:artifactId coordinates whose jars are never cached, see locate()
    private final Set<String> cacheExclusions;
    final JarCache cache;

    MavenSourcesResolver(JasoloConfig config) {
        this.localRepoPath = Path.of(config.maven().localRepo());
        this.cache = new JarCache(config.maven().cache().maxJars(), config.maven().cache().maxFiles());
        this.cacheExclusions = config.maven().cache().exclude().orElseGet(List::of).stream()
                .filter(exclusion -> exclusion != null && !exclusion.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
        // fail fast at startup instead of on the first tool call that falls
        // through to this resolver
        if (!Files.isDirectory(localRepoPath)) {
            throw new IllegalStateException("The local Maven repository does not exist: " + localRepoPath
                    + " - configure an existing directory via jasolo.maven.local-repo");
        }
    }

    @Override
    public String name() {
        return "m2-sources-jar";
    }

    @Override
    public Optional<LocatedSources> locate(Gav gav) {
        Path jar = sourcesJar(gav);
        if (jar == null || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        if (cacheExclusions.contains(gav.ga())) {
            LOG.debugf("Jar excluded from caching: %s", jar);
            return Optional.of(uncached(jar));
        }
        return Optional.of(cache.locate(jar));
    }

    /**
     * @return a {@link JarSources} instance that is not retained anywhere -
     *         every read opens the jar, so changes on disk are always visible
     */
    private static LocatedSources uncached(Path jar) {
        // lastModified/size are never compared for staleness (the instance is
        // not stored in the cache); maxFiles = 0 disables the content cache
        return new JarSources(jar, 0, 0, 0);
    }

    Path sourcesJar(Gav gav) {
        Path artifactDir = localRepoPath
                .resolve(gav.groupId().replace('.', '/'))
                .resolve(gav.artifactId())
                .resolve(gav.version());
        return artifactDir.resolve(gav.artifactId() + "-" + gav.version() + "-sources.jar");
    }

    /**
     * LRU cache of located jars, keyed by jar path. An entry is invalidated
     * when the jar file changes on disk (modification time and size).
     */
    static final class JarCache {

        private final int maxJars;
        private final int maxFiles;
        // access-ordered: the least recently used entry comes first
        private final Map<Path, JarSources> jars = new LinkedHashMap<>(16, 0.75f, true);

        JarCache(int maxJars, int maxFiles) {
            this.maxJars = maxJars;
            this.maxFiles = maxFiles;
        }

        /**
         * @return the (possibly cached) {@link JarSources} for the given jar;
         *         {@code max-jars = 0} disables caching - a new instance is
         *         created every time
         */
        synchronized LocatedSources locate(Path jar) {
            JarSources sources = jars.get(jar);
            try {
                BasicFileAttributes attrs = Files.readAttributes(jar, BasicFileAttributes.class);
                if (sources == null || sources.lastModified != attrs.lastModifiedTime().toMillis()
                        || sources.size != attrs.size()) {
                    if (sources == null) {
                        LOG.debugf("Jar cache miss: %s", jar);
                    } else {
                        LOG.debugf("Cached jar changed on disk, invalidating: %s", jar);
                    }
                    sources = new JarSources(jar, attrs.lastModifiedTime().toMillis(), attrs.size(), maxFiles);
                    if (maxJars > 0) {
                        jars.put(jar, sources);
                        while (jars.size() > maxJars) {
                            Iterator<Path> eldest = jars.keySet().iterator();
                            Path eldestJar = eldest.next();
                            eldest.remove();
                            LOG.debugf("Jar cache full (%d entries), evicted: %s", maxJars, eldestJar);
                        }
                    }
                } else {
                    LOG.debugf("Jar cache hit: %s", jar);
                }
                return sources;
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read attributes of " + jar, e);
            }
        }

        boolean contains(Path jar) {
            synchronized (this) {
                return jars.containsKey(jar);
            }
        }
    }

    static class JarSources implements LocatedSources {

        private final Path jar;
        private final long lastModified;
        private final long size;
        private final int maxFiles;
        // lazily computed fqcn listing
        private volatile List<String> fqcns;
        // access-ordered LRU of file contents
        private final Map<String, String> contents = new LinkedHashMap<>(16, 0.75f, true);

        JarSources(Path jar, long lastModified, long size, int maxFiles) {
            this.jar = jar;
            this.lastModified = lastModified;
            this.size = size;
            this.maxFiles = maxFiles;
        }

        @Override
        public String origin() {
            return "maven sources jar " + jar;
        }

        @Override
        public Optional<String> read(String fqcn) {
            String cached;
            synchronized (contents) {
                cached = maxFiles > 0 ? contents.get(fqcn) : null;
            }
            if (cached != null) {
                LOG.debugf("Content cache hit: %s in %s", fqcn, jar);
                return Optional.of(cached);
            }
            LOG.debugf("Content cache miss: %s in %s", fqcn, jar);
            String content = readJarEntry(fqcn);
            if (content == null) {
                // misses are not cached - a failed lookup only costs one jar open
                return Optional.empty();
            }
            if (maxFiles > 0) {
                synchronized (contents) {
                    contents.put(fqcn, content);
                    while (contents.size() > maxFiles) {
                        Iterator<String> eldest = contents.keySet().iterator();
                        eldest.next();
                        eldest.remove();
                    }
                }
            }
            return Optional.of(content);
        }

        @Override
        public List<String> listFqcns() {
            List<String> result = fqcns;
            if (result == null) {
                synchronized (this) {
                    result = fqcns;
                    if (result == null) {
                        result = computeFqcns();
                        fqcns = result;
                        LOG.debugf("Computed fqcn listing of %s: %d classes", jar, result.size());
                    }
                }
            }
            return result;
        }

        private String readJarEntry(String fqcn) {
            String entryPath = fqcn.replace('.', '/') + ".java";
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                // most sources jars are rooted at the package, some at src/main/java
                JarEntry entry = jarFile.getJarEntry(entryPath);
                if (entry == null) {
                    entry = jarFile.getJarEntry("src/main/java/" + entryPath);
                }
                if (entry == null) {
                    return null;
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read " + jar, e);
            }
        }

        private List<String> computeFqcns() {
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();
                return Collections.list(entries).stream()
                        .map(JarEntry::getName)
                        .filter(name -> name.endsWith(".java"))
                        // most sources jars are rooted at the package, some at src/main/java
                        .map(name -> name.startsWith("src/main/java/")
                                ? name.substring("src/main/java/".length())
                                : name)
                        // require at least one package segment (drops module-info.java etc.)
                        .filter(name -> name.contains("/") && !name.startsWith("/"))
                        .map(name -> name.substring(0, name.length() - ".java".length()).replace('/', '.'))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read " + jar, e);
            }
        }
    }
}
