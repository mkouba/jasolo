# jasolo

An **MCP server that locates and reads Java source code**,
built on the [Quarkus MCP server](https://github.com/quarkiverse/quarkus-mcp-server).

Given a fully qualified class name (FQCN) and Maven coordinates (GAV), jasolo
finds the source file - preferring your **local git clones** (including
branch/tag switching for specific versions) and falling back to the
**sources jars in your local Maven repository**.

jasolo is for **sources of dependency artifacts**. It is not intended for
reading files of the project you are currently working on - use your
client's regular file tools for those.

## Installation

### Via JBang (recommended)

With [JBang](https://www.jbang.dev/download/) installed, register jasolo
with your MCP client:

```bash
claude mcp add -s user jasolo \
  -e JASOLO_GIT_ROOTS=$HOME/source \
  -- jbang jasolo@mkouba/jasolo
```

JBang resolves the released uber-jar from
[GitHub Releases](https://github.com/mkouba/jasolo/releases) via the
[`jbang-catalog.json`](jbang-catalog.json) in this repository and provisions
a JDK 21+ if needed - no build step. To pick up a newly released version,
force a cache refresh once:

```bash
jbang --fresh jasolo@mkouba/jasolo
```

### Via native binary

Pre-built native executables are attached to each
[GitHub Release](https://github.com/mkouba/jasolo/releases/latest). They
start in milliseconds and need no JVM or JBang installation:

```bash
curl -sLO https://github.com/mkouba/jasolo/releases/download/0.1.0/jasolo-0.1.0-linux-x86_64
chmod +x jasolo-0.1.0-linux-x86_64
claude mcp add -s user jasolo \
  -e JASOLO_GIT_ROOTS=$HOME/source \
  -- /path/to/jasolo-0.1.0-linux-x86_64
```

### Direct download or from source

Download the uber-jar from the
[latest GitHub Release](https://github.com/mkouba/jasolo/releases/latest),
or build it yourself - see [Building and running](#building-and-running).

## Why an MCP server?

An agent could shell out to `unzip`/`grep` instead - but for an agent, the
MCP tools are cheaper in roundtrips and context window:

- **Fewer roundtrips** - locating and reading a class from a dependency is a
  single `getSource` call instead of `ls` + `unzip -l` + `unzip -p`, with no
  failed guesses on entry paths (package-rooted vs. `src/main/java` jars,
  module directories, nested classes and test sources are all resolved
  server-side)
- **Bounded context consumption** - `startLine`/`endLine` slice large files
  deliberately and `totalLines` reveals truncation, so reading 60 lines of a
  1000-line file is a choice, not an accident
- **Structured results** - typed JSON instead of command output to parse:
  `listClasses` returns FQCNs (not jar entry paths), `grepSource` returns
  capped `{line, text}` matches

## Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `getSource` | `fqcn`, `gav`, `startLine?`, `endLine?` | Structured result: `filename` (path relative to the source root), `version`, `origin` (where the source was resolved from), `content` and `totalLines`. A nested class such as `Foo$Bar` is resolved to its top-level file. Test sources (`src/test/java`) are also readable. The optional `startLine`/`endLine` (1-based, inclusive) return only that slice; `endLine` is clamped to the end of the file and `totalLines` always refers to the whole file. Omitting `startLine` and setting only `endLine` caps the response to the first lines of the file; a `totalLines` greater than `endLine` then indicates truncation. |
| `grepSource` | `fqcn`, `gav`, `pattern`, `ignoreCase?` | Searches the source file of a class (resolved like `getSource`) with a regular expression. Structured result: `count` (total matching lines), `truncated` and `matches` - a list of `{line, text}` objects capped at 500 entries. |
| `grepArtifact` | `gav`, `pattern`, `ignoreCase?` | Searches **all main source files** of the artifact (resolved like `getSource`) with a regular expression - one call instead of one `grepSource` per class. Structured result: `filesSearched`, `filesWithMatches`, `count` (total matching lines), `truncated` and `files` - one `{fqcn, count, truncated, matches}` entry per file with at least one match, in class name order; files without matches are omitted. The total number of collected `{line, text}` matches is capped at 500, per-file counts are always complete. |
| `listClasses` | `gav` | Structured listing of all main-source FQCNs available for the artifact. |

All tools return structured content only; the structured result is not
mirrored as text content, which keeps the responses lean. A result may carry
a `warnings` list (omitted when empty), e.g. when the sources are not an
exact match of the requested version - see below.

All tools take a `gav` in the form `groupId:artifactId:version`, e.g.
`io.quarkus:quarkus-core:3.27.5.1`.

## Resolution chain

Resolvers are tried in the order of their `@Priority` (extensible via CDI -
implement `SourceResolver` and register a bean; the **higher** the
`@Priority` value, the earlier the resolver is tried - Arc injects an
`@All` list sorted by descending priority):

1. **`git-repo`** - the artifact is produced by a git repo cloned under one of
   the configured `jasolo.git.roots`:
   - the repo's `pom.xml` modules are indexed at first use
     (`groupId:artifactId` → repo + module directory)
   - the GAV version is mapped to a git ref (see below) and the file is read
     **non-destructively** with `git show <ref>:<path>` - your working copy is
     never touched
2. **`m2-sources-jar`** - `<artifactId>-<version>-sources.jar` in the local
   Maven repository

A resolver may locate the sources only **approximately** - e.g. a release
version read from a maintenance branch because the release tag is missing.
An exact match of a later resolver wins then (e.g. the sources jar of exactly
that version); the approximate match is used only if no resolver can locate
the GAV exactly, and the tool result carries a warning.

If no resolver can locate the GAV, the tool returns an error explaining what
was tried.

The configuration is validated at startup, so a misconfigured application
fails fast instead of failing on the first tool call:

- the local Maven repository (`jasolo.maven.local-repo`) must be an existing
  directory
- when git roots are configured, every root must exist on disk and the
  selected git access strategy must meet its prerequisites (the `show`
  strategy requires the git CLI on the `PATH`)
- with no git roots configured the git resolver is disabled (m2-only mode)
  and no git tooling is needed

### Version → git ref mapping

- versions ending with `-SNAPSHOT` resolve to the default branch (`main`,
  then `master`)
- otherwise the configured ref patterns are tried in order; the first
  ref that exists wins:
  1. tag `{version}`
  2. tag `v{version}`
  3. branch `{major}.{minor}` (e.g. the Quarkus maintenance branch `3.27`)
  4. branch `{major}.{minor}.x`
  5. branch `development/{major}.{minor}`

For example, `io.quarkus:quarkus-core:3.27.5.1` resolves to tag `3.27.5.1` of
a clone of the Quarkus repository, while `io.quarkus:quarkus-core:999-SNAPSHOT`
resolves to its `main` branch.

A tag (or the default branch of a snapshot version) is an exact match. A
release version that only matches a branch is an approximate match: the
branch may have moved past the release, so the m2 sources jar of that
version is preferred if present; otherwise the branch is read and the result
warns that the tag is missing (e.g. `git fetch --tags` was not run).

The ref set of each repository is cached; when a version matches no cached
ref, or only a branch, the cache is refreshed once - so newly fetched tags
and branches are picked up without restarting the server.

## Configuration

`application.properties` (or environment variables):

| Property | Default | Description |
|----------|---------|-------------|
| `jasolo.git.roots` | *(empty)* | Comma-separated list of directories whose immediate children are cloned git repos, e.g. `/home/me/source,/home/me/source/quarkiverse`. Empty disables the git resolver. |
| `jasolo.git.access-mode` | `show` | Git access strategy. `show` reads files from a ref without touching the working copy. The `GitAccessStrategy` SPI allows other modes (e.g. real checkouts). |
| `jasolo.git.ref-patterns` | `{version},v{version},{major}.{minor},{major}.{minor}.x,development/{major}.{minor}` | Ordered ref patterns; placeholders `{version}`, `{major}`, `{minor}`. |
| `jasolo.maven.local-repo` | `~/.m2/repository` | Local Maven repository path. |
| `jasolo.maven.cache.max-jars` | `16` | Located sources jars kept in an LRU cache; a jar is re-read when it changes on disk (timestamp/size). `0` disables caching. |
| `jasolo.maven.cache.max-files` | `256` | Source file contents cached per located jar (LRU). `0` disables content caching (the FQCN listing is still cached). |
| `jasolo.maven.cache.exclude` | *(empty)* | `groupId:artifactId` coordinates whose sources jars are never cached, e.g. known large jars - every read of an excluded artifact goes to disk. |

## Building and running

The MCP transport is selected by a Maven profile:

| Build | Transport | Artifact |
|-------|-----------|----------|
| `mvn package` (default) | streamable HTTP at `/mcp` (legacy SSE at `/mcp/sse`) | `target/quarkus-app/quarkus-run.jar` |
| `mvn package -Dprod` | **stdio** | uber-jar `target/jasolo-<version>-runner.jar` |
| `mvn package -Dprod -Dnative` | **stdio** | native executable `target/jasolo-<version>-runner` |

The default (HTTP) build is meant for development - dev mode, the MCP
Inspector, `curl`. Production deployments use the stdio builds: the MCP
client launches jasolo as a subprocess, no port is opened and no server has
to be kept running.

Over stdio, stdout carries the MCP protocol; the log goes to stderr (where
MCP clients usually collect it).

### Production: uber-jar

```bash
mvn package -Dprod
claude mcp add -s user jasolo \
  -e JASOLO_GIT_ROOTS=$HOME/source \
  -- java -jar /path/to/jasolo/target/jasolo-<version>-runner.jar
```

Requires Java 21+. Use an absolute path to `java` if the MCP client is not
launched from a shell (e.g. a desktop app) and `java` is only on the shell
`PATH`.

### Production: native executable

A native executable starts in milliseconds and needs no JVM - a good fit for
a process that the MCP client spawns per session.

```bash
# with a local GraalVM / Mandrel
mvn package -Dprod -Dnative
# or build inside a container (Linux executable), no local GraalVM needed
mvn package -Dprod -Dnative -Dquarkus.native.container-build=true

claude mcp add -s user jasolo \
  -e JASOLO_GIT_ROOTS=$HOME/source \
  -- /path/to/jasolo/target/jasolo-<version>-runner
```

The `show` git access strategy runs the `git` CLI, which must be on the
`PATH` of the server process in both the JVM and native modes.

Configuration is passed as environment variables
(`JASOLO_GIT_ROOTS` → `jasolo.git.roots`, `JASOLO_MAVEN_LOCAL_REPO` →
`jasolo.maven.local-repo`, ...) or system properties
(`java -Djasolo.git.roots=... -jar ...`, `jasolo-runner -Djasolo.git.roots=...`).

### Development: HTTP

```bash
mvn quarkus:dev
claude mcp add --transport http jasolo http://localhost:8080/mcp
```

In dev mode, `jasolo.git.roots` defaults to `~/source`.

## Tests

```bash
mvn test
```

Unit tests cover GAV parsing, the repo index (pom scanning with coordinate
inheritance), both resolvers and the resolver chain; the tool layer is tested
with the Quarkus MCP test client against fixture git repos and sources jars.

```bash
mvn verify -Dprod            # + stdio integration tests against the uber-jar
mvn verify -Dprod -Dnative   # + stdio integration tests against the native executable
```

The production builds additionally run `SourceLocatorToolsIT`, which launches
the packaged artifact and talks to it over stdio.

## Releasing

jasolo is not published to Maven Central - the released artifacts are
attached to a GitHub Release: the stdio uber-jar (exposed to JBang through
the repo-root `jbang-catalog.json`) and native executables.

1. Set the release version: `mvn versions:set -DnewVersion=0.1.0`
2. Point the `script-ref` in `jbang-catalog.json` at the release asset URL
   of that version
3. Build and test the release artifacts:
   `mvn clean verify -Dprod` (uber-jar) and
   `mvn verify -Dprod -Dnative` (native executable; use
   `-Dquarkus.native.container-build=true` to build for a foreign platform)
4. Commit, tag (`0.1.0`) and push, including the tag
5. Create a GitHub Release for the tag and attach:
   - `target/jasolo-0.1.0-runner.jar` (the uber-jar)
   - `target/jasolo-0.1.0-runner` renamed to
     `jasolo-0.1.0-<os>-<arch>` (e.g. `jasolo-0.1.0-linux-x86_64`) - one
     file per platform you built for
6. Bump the version to the next development version
   (`mvn versions:set -DnewVersion=0.2.0-SNAPSHOT`) and commit
