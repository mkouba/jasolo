package com.github.mkouba.jasolo;

import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkus.arc.All;
import jakarta.inject.Singleton;

/**
 * The ordered resolver chain. The first resolver that can locate a GAV
 * exactly wins (see {@link LocatedSources#exact()}). An approximate match,
 * e.g. a release version read from a git maintenance branch, is only used
 * if no later resolver can locate the GAV exactly; if no resolver can locate
 * the GAV at all, a {@link SourceNotFoundException} is thrown.
 */
@Singleton
public class SourceLocator {

    private static final Logger LOG = Logger.getLogger(SourceLocator.class);

    private final List<SourceResolver> resolvers;

    SourceLocator(@All List<SourceResolver> resolvers) {
        this.resolvers = resolvers;
    }

    /**
     * @param gav the GAV to locate
     * @return the located sources
     * @throws SourceNotFoundException if no resolver can locate the GAV
     */
    public LocatedSources locate(Gav gav) {
        List<String> tried = new ArrayList<>();
        // the first approximate match, used if no exact match is found
        LocatedSources approximate = null;
        for (SourceResolver resolver : resolvers) {
            long start = System.nanoTime();
            var located = resolver.locate(gav);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (located.isPresent()) {
                if (located.get().exact()) {
                    LOG.infof("%s located by %s in %d ms: %s", gav, resolver.name(), elapsedMs,
                            located.get().origin());
                    return located.get();
                }
                LOG.debugf("%s approximately located by %s in %d ms: %s", gav, resolver.name(), elapsedMs,
                        located.get().origin());
                if (approximate == null) {
                    approximate = located.get();
                }
            } else {
                LOG.debugf("%s declined by %s in %d ms", gav, resolver.name(), elapsedMs);
            }
            tried.add(resolver.name());
        }
        if (approximate != null) {
            LOG.infof("%s located approximately, no exact match found: %s", gav, approximate.origin());
            return approximate;
        }
        LOG.debugf("No resolver could locate %s, tried: %s", gav, tried);
        throw new SourceNotFoundException(gav, tried);
    }

    List<String> resolverNames() {
        return resolvers.stream().map(SourceResolver::name).toList();
    }

}
