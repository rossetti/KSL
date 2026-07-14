# KSL Book MCP Server

An MCP server that gives Claude Desktop (and other MCP clients) searchable access to the
[KSL simulation textbook](https://rossetti.github.io/KSLBook/). The rendered Quarto HTML in
the repo-root `_book/` is parsed at build time into `chunks.json` + `exercises.json`, bundled
into a self-contained jar, and searched in memory with Lucene.

## Installing (students)

1. Install Java 21 or newer (already required for the KSL model server).
2. Download `ksl-book-mcp.jar` from the course link.
3. Double-click the jar (or run `java -jar ksl-book-mcp.jar`): the setup window
   detects Claude Desktop / Codex and wires the server in. Console alternative:
   `java -jar ksl-book-mcp.jar --setup`.
4. Restart your agent; the tools icon should show the `ksl-book` server (alongside
   `ksl` if you also installed the model server).
5. Troubleshooting: `java -jar ksl-book-mcp.jar --doctor` prints the version and
   content stats; `--remove` cleanly un-registers the server (your agent config is
   backed up on first change as `*.ksl-book-backup`).

### Getting Claude Desktop to actually use the book

The server sends MCP `instructions` during the handshake telling the client to
search this textbook before web search or general knowledge for any
simulation/course question — no student action needed. If routing still feels
lazy, a Claude Desktop **Project** for the course with these custom instructions
makes it airtight:

> Questions in this project concern my simulation course, which uses the KSL
> textbook. Always search the textbook first (ksl-book tools) and cite section
> URLs in your answers.

Manual configuration for any other agent — add to its MCP servers config
(Claude Desktop: `claude_desktop_config.json`, macOS
`~/Library/Application Support/Claude/`, Windows `%APPDATA%\Claude\`):

```json
{
  "mcpServers": {
    "ksl-book": { "command": "java", "args": ["-jar", "/path/to/ksl-book-mcp.jar", "--stdio"] }
  }
}
```

## Building

Requires JDK 21 and a rendered Quarto book in the repo-root `_book/` (git-ignored — copy the
`quarto render` output there). If `_book/` is absent the build still succeeds but bundles empty
content, so the whole-repo build stays green on a fresh clone. Run from the KSL repo root:

```bash
./gradlew :KSLBookServer:generateBookContent   # parse _book/ into build/generated/book/
./gradlew :KSLBookServer:test                  # conversion + structure tests
./gradlew :KSLBookServer:shadowJar             # KSLBookServer/build/libs/ksl-book-mcp.jar
```

## Running

```bash
java -jar KSLBookServer/build/libs/ksl-book-mcp.jar --doctor  # version + content stats
java -jar KSLBookServer/build/libs/ksl-book-mcp.jar --stdio   # MCP server (what Claude Desktop runs)
java -jar KSLBookServer/build/libs/ksl-book-mcp.jar --setup   # wire detected agents (console)
java -jar KSLBookServer/build/libs/ksl-book-mcp.jar --gui     # setup window (also: double-click the jar)
java -jar KSLBookServer/build/libs/ksl-book-mcp.jar --remove  # un-register from detected agents
```

Tools: `search_textbook`, `get_section`, `get_chapter_outline`, `list_chapters`,
`get_exercises`, `get_related_sections`.

Interactive dev loop via the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector java -jar KSLBookServer/build/libs/ksl-book-mcp.jar --stdio
```

Claude Desktop dev config (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "ksl-book-dev": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/KSL/KSLBookServer/build/libs/ksl-book-mcp.jar", "--stdio"]
    }
  }
}
```

In `--stdio` mode stdout carries only JSON-RPC; all logging goes to stderr
(`logback-ksl-book-mcp.xml`). When smoke-testing by piping JSON-RPC lines in,
pace the messages (small sleeps between them) and keep stdin open — on an
abrupt EOF the SDK closes the session before answering buffered requests.

`generateBookContent` prints a summary (chunk/exercise counts, size stats) and fails on
duplicate section ids. Generated content is never committed; it is rebuilt from `_book/` on
every build (and is empty when `_book/` is absent, e.g. a fresh clone or CI).

## Topic keywords (`topics.json`)

`topics.json` maps section ids to curated search keywords (`{"sec-introDEDSPharmacy":
["drive through pharmacy", ...]}`). The chunker merges them at build time and the
search indexes them between title and body weight, bridging vocabulary gaps between
student phrasing and section titles ("warm up period" vs "initial conditions").

Maintenance: the sidecar is keyed by section id, so it survives renumbering and
reordering. After a restructure, the build prints a warning listing any ids that no
longer exist, and new sections simply have no keywords until added — the build never
fails over topics. Regenerate or hand-edit the file and commit it; keys should follow
book order to keep diffs readable.

## Releases

Built and released **manually** — see [RELEASING.md](RELEASING.md). In short: render the book,
copy its output into `_book/`, run `./gradlew :KSLBookServer:shadowJar`, and attach the jar to a
GitHub Release (or hand it to students directly). There is no book-specific CI.

Book update flow: re-render the book (Quarto), copy the output into `_book/`, and rebuild the
jar — chunk content and citation URLs are baked in at build time, so the jar must be rebuilt
whenever the book changes. The citation URLs resolve in a browser only once the matching render
is published to the book's GitHub Pages site (`KSLBook/docs/`).

Folding the Book server into the one-command KSL installer (`KSLWork`) is being designed
separately under the KSL distribution plan; this manual flow is the interim.

## Layout

- `src/main/kotlin/ksl/book/gen` — build-time content generator (TOC parser, page
  chunker, HTML-to-markdown conversion)
- `src/main/kotlin/ksl/book/mcp` — data model and MCP server
- `src/test/kotlin` — conversion golden tests plus integration tests that run against
  the real `../_book` pages
