# KSL Book MCP Server

An MCP server that gives Claude Desktop (and other MCP clients) searchable access to the
[KSL simulation textbook](https://rossetti.github.io/KSLBook/). The rendered HTML in
`../docs` is parsed at build time into `chunks.json` + `exercises.json`, bundled into a
self-contained jar, and searched in memory with Lucene.

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

Requires JDK 21.

```bash
./gradlew generateBookContent   # parse docs/ into build/generated/book/
./gradlew test                  # conversion + structure tests
./gradlew shadowJar             # build/libs/ksl-book-mcp.jar
```

## Running

```bash
java -jar build/libs/ksl-book-mcp.jar --doctor    # version + content stats
java -jar build/libs/ksl-book-mcp.jar --stdio     # MCP server (what Claude Desktop runs)
java -jar build/libs/ksl-book-mcp.jar --setup     # wire detected agents (console)
java -jar build/libs/ksl-book-mcp.jar --gui       # setup window (also: double-click the jar)
java -jar build/libs/ksl-book-mcp.jar --remove    # un-register from detected agents
```

Tools: `search_textbook`, `get_section`, `get_chapter_outline`, `list_chapters`,
`get_exercises`, `get_related_sections`.

Interactive dev loop via the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector java -jar build/libs/ksl-book-mcp.jar --stdio
```

Claude Desktop dev config (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "ksl-book-dev": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/KSLBook/mcp-server/build/libs/ksl-book-mcp.jar", "--stdio"]
    }
  }
}
```

In `--stdio` mode stdout carries only JSON-RPC; all logging goes to stderr
(`logback-ksl-book-mcp.xml`). When smoke-testing by piping JSON-RPC lines in,
pace the messages (small sleeps between them) and keep stdin open — on an
abrupt EOF the SDK closes the session before answering buffered requests.

`generateBookContent` prints a summary (chunk/exercise counts, size stats) and fails on
duplicate section ids. Generated content is never committed; it is rebuilt from `docs/`
on every build.

## Topic keywords (`topics.json`)

`topics.json` maps section ids to curated search keywords (`{"introDEDSPharmacy":
["drive through pharmacy", ...]}`). The chunker merges them at build time and the
search indexes them between title and body weight, bridging vocabulary gaps between
student phrasing and section titles ("warm up period" vs "initial conditions").

Maintenance: the sidecar is keyed by section id, so it survives renumbering and
reordering. After a restructure, the build prints a warning listing any ids that no
longer exist, and new sections simply have no keywords until added — the build never
fails over topics. Regenerate or hand-edit the file and commit it; keys should follow
book order to keep diffs readable.

## Releases

CI (`.github/workflows/build-mcp.yml`) builds and tests the jar on every pull
request and on every push to `main` that touches `docs/` or `mcp-server/`. The jar
is attached to each run as a workflow artifact.

- **Rolling** (testing): a push to `main` updates the `book-mcp-latest` release —
  `https://github.com/rossetti/KSLBook/releases/download/book-mcp-latest/ksl-book-mcp.jar`
- **Pinned semester** (syllabus link): run the workflow manually with a
  `release_tag` (e.g. `book-mcp-f26`) —
  `https://github.com/rossetti/KSLBook/releases/download/book-mcp-f26/ksl-book-mcp.jar`

Book update flow: edit the Rmd source (private repo), rebuild the book, push the
HTML into `docs/` as usual — CI rebuilds the jar and refreshes the rolling release
automatically. Semester releases are one manual dispatch.

## Layout

- `src/main/kotlin/ksl/book/gen` — build-time content generator (TOC parser, page
  chunker, HTML-to-markdown conversion)
- `src/main/kotlin/ksl/book/mcp` — data model and (upcoming) MCP server
- `src/test/kotlin` — conversion golden tests plus integration tests that run against
  the real `../docs` pages
