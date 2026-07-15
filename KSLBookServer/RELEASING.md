# Building the KSL Book MCP Server

The Book server ships as part of the **KSL suite**: `assembleKSLWork` bundles its
self-contained jar into `ksl-suite.zip` as `Servers/book/`, and students get it from the
one-command installer. There is no standalone `book-mcp-*` release channel and no
book-specific CI — the jar is rebuilt on every suite build.

This page covers the one thing the suite build **can't** do for you: rendering the book
content that the jar bakes in. To cut a release, see [docs/releasing-suite.md](../docs/releasing-suite.md).

## Prerequisites

- JDK 21.
- A rendered Quarto book in the repo-root `_book/` (git-ignored). Render the book with
  `quarto render` and copy its `_book/` output into the KSL repo root. The jar bakes this
  content in at build time, so a build **without** `_book/` produces an empty (non-functional)
  jar rather than failing.

## Build and verify the jar

From the KSL repo root:

```bash
./gradlew :KSLBookServer:shadowJar
```

Output: `KSLBookServer/build/libs/ksl-book-mcp.jar`. Verify it:

```bash
java -jar KSLBookServer/build/libs/ksl-book-mcp.jar --doctor
```

`--doctor` prints the version and content stats (chunk / exercise / chapter counts) and
confirms the bundled book loads. If it reports **0 chunks**, `_book/` was missing or empty at
build time — render it, copy it in, and rebuild. The suite release runbook runs this same
check against the bundled `Servers/book/` jar before publishing, so a content-less book
server can't ship unnoticed.

## Install locally

The jar self-installs into MCP clients:

```bash
java -jar ksl-book-mcp.jar --setup     # wire detected Claude Desktop / Codex (console)
java -jar ksl-book-mcp.jar --gui       # setup window (also: double-click the jar)
java -jar ksl-book-mcp.jar --remove    # un-register cleanly (client configs are backed up)
```

## The coupling to remember

Rebuild and re-release the **suite** whenever you re-render the book — chunk content and the
citation URLs are baked into the jar at build time. The citation URLs point at the book's
GitHub Pages site (`https://rossetti.github.io/KSLBook/…`), so they resolve in a browser only
after the **matching** render is published there (the `KSLBook` repo's `docs/`). Publish the
render and rebuild the suite together, so the bundled anchors and the live site agree.
