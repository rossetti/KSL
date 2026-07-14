# Releasing the KSL Book MCP Server

A short, manual process — there is **no book-specific CI**. (Folding the Book server into the
one-command KSL installer is being designed separately under the KSL distribution plan; until
then, releases are built and published by hand as described here.)

## Prerequisites

- JDK 21.
- A rendered Quarto book in the repo-root `_book/` (git-ignored). Render the book with
  `quarto render` and copy its `_book/` output into the KSL repo root. The jar bakes this
  content in at build time, so a build **without** `_book/` produces an empty (non-functional)
  jar rather than failing.

## Build the jar

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
build time — render it, copy it in, and rebuild.

## Install locally

The jar self-installs into MCP clients:

```bash
java -jar ksl-book-mcp.jar --setup     # wire detected Claude Desktop / Codex (console)
java -jar ksl-book-mcp.jar --gui       # setup window (also: double-click the jar)
java -jar ksl-book-mcp.jar --remove    # un-register cleanly (client configs are backed up)
```

## Distribute to students (optional)

Attach the jar to a GitHub Release by hand, e.g.:

```bash
gh release create book-mcp-f26 KSLBookServer/build/libs/ksl-book-mcp.jar
```

…or share the file directly (course LMS, shared drive). Students download it and run `--setup`
(or double-click the jar).

## The coupling to remember

Rebuild and redistribute the jar **whenever you re-render the book** — chunk content and the
citation URLs are baked into the jar at build time. The citation URLs point at the book's
GitHub Pages site (`https://rossetti.github.io/KSLBook/…`), so they resolve in a browser only
after the **matching** render is published there (the `KSLBook` repo's `docs/`). Publish the
render and rebuild the jar together, so the bundled anchors and the live site agree.
