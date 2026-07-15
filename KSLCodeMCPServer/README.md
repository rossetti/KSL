# KSL Code MCP Server

An MCP server that gives Claude Desktop (and other MCP clients) searchable access to the
**source code** of the [Kotlin Simulation Library (KSL)](https://github.com/rossetti/KSL) —
the public API of `KSLCore` and the worked programs in `KSLExamples`. The `.kt` source is
parsed at build time (via the Kotlin compiler's PSI) into `chunks.json`, bundled into a
self-contained jar, and searched in memory with Lucene.

It is the third KSL student server, cleanly separated from the other two:

| Server | Answers questions about |
|---|---|
| `ksl` (KSLServerMcp) | Running pre-built simulation models, experiments, optimization, distribution fitting |
| `ksl-book` (KSLBook/mcp-server) | Textbook concepts, theory, worked examples from the book |
| `ksl-code` (this server) | KSL API declarations, KDoc, source structure, code examples |

All three run as separate JVM processes; Claude routes tool calls by context without any
student configuration.

## Installing (students)

1. Install Java 21 or newer (already required for the other KSL servers).
2. Download `ksl-code-mcp.jar` from the course link.
3. Double-click the jar (or run `java -jar ksl-code-mcp.jar`): the setup window detects
   Claude Desktop / Codex and wires the server in. Console alternative:
   `java -jar ksl-code-mcp.jar --setup`.
4. Restart your agent; the tools icon should show the `ksl-code` server (alongside `ksl`
   and `ksl-book` if you also installed those).
5. Troubleshooting: `java -jar ksl-code-mcp.jar --doctor` prints the version and index
   stats; `--remove` cleanly un-registers the server (your agent config is backed up on
   first change as `*.ksl-code-backup`).

Manual configuration for any other agent — add to its MCP servers config (Claude Desktop:
`claude_desktop_config.json`, macOS `~/Library/Application Support/Claude/`, Windows
`%APPDATA%\Claude\`):

```json
{
  "mcpServers": {
    "ksl-code": { "command": "java", "args": ["-jar", "/path/to/ksl-code-mcp.jar", "--stdio"] }
  }
}
```

## Tools

| Tool | Purpose |
|---|---|
| `search_code` | Full-text search over the KSL API and examples (the entry point) |
| `get_class` | Full API of one declaration: signature, supertypes, KDoc, members, examples |
| `get_example` | KSLExamples files that use a given declaration |
| `get_package_overview` | Every public declaration in a package, with one-line summaries |
| `find_subclasses` | Declarations that extend/implement a given type |
| `get_related_examples` | KSLExamples programs related to a topic |
| `list_modules` | Modules, declaration counts, and package lists |
| `get_server_info` | KSL ref indexed, build date, declaration count |

## Building

Requires JDK 21. It is a **module of the KSL root build** (`:KSLCodeMCPServer`); build it from
the KSL repository root:

```bash
./gradlew :KSLCodeMCPServer:generateCodeContent   # parse KSLCore + KSLExamples into build/generated/code/
./gradlew :KSLCodeMCPServer:test                  # unit + index tests
./gradlew :KSLCodeMCPServer:shadowJar             # KSLCodeMCPServer/build/libs/ksl-code-mcp.jar
```

`generateCodeContent` reads the sibling `KSLCore/src/main/kotlin` and
`KSLExamples/src/main/kotlin` source trees. It prints a summary (declaration counts by
module and kind, KDoc coverage, example cross-links) and fails on duplicate ids. Generated
content is never committed; it is rebuilt from source on every build.

### Pinning the KSL version

The bundled index corresponds to a specific KSL git ref, used in the citation URLs and
reported by `get_server_info`. Pin it for a course build:

```bash
./gradlew :KSLCodeMCPServer:shadowJar -PkslVersion=v2.0.1
```

Default is `develop`. For a course, check out the KSL tag your `build.gradle` depends on,
then build with the matching `-PkslVersion`.

## Running

```bash
java -jar build/libs/ksl-code-mcp.jar --doctor    # version + index stats
java -jar build/libs/ksl-code-mcp.jar --stdio     # MCP server (what Claude Desktop runs)
java -jar build/libs/ksl-code-mcp.jar --setup     # wire detected agents (console)
java -jar build/libs/ksl-code-mcp.jar --gui       # setup window (also: double-click the jar)
java -jar build/libs/ksl-code-mcp.jar --remove    # un-register from detected agents
```

Dev search harness:

```bash
./gradlew searchCode -Pq="seize release a resource"
./gradlew searchCode -Pq="subclasses ModelElement"
```

Interactive MCP dev loop via the Inspector:

```bash
npx @modelcontextprotocol/inspector java -jar build/libs/ksl-code-mcp.jar --stdio
```

In `--stdio` mode stdout carries only JSON-RPC; all logging goes to stderr
(`logback-ksl-code-mcp.xml`).

## Topic keywords (`topics.json`)

`topics.json` maps declaration fully-qualified names to curated search keywords
(`{"ksl.modeling.entity.Resource": ["resource seize release", ...]}`). The extractor merges
them at build time and the search indexes them with a boost, bridging vocabulary gaps
between student phrasing and code names ("seize a server" vs `Resource`). The sidecar is
keyed by fqn, so it survives line moves; the build warns about keys that no longer resolve
and never fails over topics. Hand-edit and commit it; new declarations simply have no
keywords until added.

## Layout

- `src/gen/kotlin/ksl/code/gen` — build-time extractor (Kotlin-PSI declaration parser,
  example cross-referencer). Depends on `kotlin-compiler-embeddable`, which is deliberately
  kept **out** of the shipped jar (a separate source set; `shadowJar` bundles only `main`).
- `src/main/kotlin/ksl/code/mcp` — the runtime server: data model, in-memory Lucene search,
  MCP tools, and the launcher / agent auto-setup ported from the book server.
- `src/test/kotlin` — parser unit tests plus integration tests against the real generated index.

## Releases

The server ships as part of the **KSL suite**: `assembleKSLWork` bundles its self-contained
jar into `ksl-suite.zip` as `Servers/code/`, and students get it from the one-command
installer. There is no standalone `code-mcp-*` release channel and no server-specific CI —
the jar, and the Lucene index of KSL source baked into it, are rebuilt from the current
source on every suite build. To cut a release, see [docs/releasing-suite.md](../docs/releasing-suite.md).
