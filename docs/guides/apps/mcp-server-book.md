# KSL Book MCP Server — User Guide

The **KSL Book MCP server** gives any MCP-capable AI assistant (Claude Desktop, Cursor,
Codex, …) **searchable access to the KSL simulation textbook**
([rossetti.github.io/KSLBook](https://rossetti.github.io/KSLBook/)). Ask your assistant a
course question ("what is a warm-up period?", "explain the drive-through pharmacy
example") and it searches the actual book — sections, chapter outlines, and end-of-chapter
exercises — and cites section URLs, instead of answering from general knowledge. The
rendered book is parsed at build time into a search index and bundled into a single
self-contained jar.

> **You will need:** Java 21 on your `PATH` and an **MCP-capable client** (this guide uses
> Claude Desktop). You'll need the `ksl-book-mcp.jar` — download it from your course link,
> or build it ([§2](#2-before-you-begin)). Unlike the desktop-app guides, this is a
> *server* your AI client drives, so the examples are **real command and tool
> transcripts**, not screenshots.

## What you'll be able to do

- Install `ksl-book-mcp.jar` and wire it into your AI client — automatically or by hand.
- Verify the connection and see the textbook tools the assistant gains.
- Have the assistant answer course questions from the actual textbook, with citations.
- Pull a section's text, a chapter outline, or a chapter's exercises on demand.
- Make the assistant *prefer* the textbook over web search for course questions.

---

## 1. At a glance

This is one of **three** separate KSL student MCP servers. Each answers a different kind
of question, each runs as its own JVM process, and your assistant routes to the right one
by context — no per-question configuration:

| Server | Answers questions about | Guide |
|---|---|---|
| `ksl` (KSLServerMcp) | **Running** models — single runs, experiments, optimization, distribution fitting | [MCP Server](mcp-server.md) |
| **`ksl-book`** (this server) | **Textbook** concepts, theory, worked examples, exercises | this guide |
| `ksl-code` (KSLCodeMCPServer) | **Source code** — API declarations, KDoc, structure | [Code MCP Server](mcp-server-code.md) |

You never call the tools yourself — the assistant does, in response to what you ask.
The six tools it gains:

| Tool | What it's for |
|---|---|
| `search_textbook` | Full-text search across the book — the entry point |
| `get_section` | The full text of one section, with its citation URL |
| `get_chapter_outline` | The section structure of one chapter |
| `list_chapters` | Every chapter in the book |
| `get_exercises` | The end-of-chapter exercises for a chapter or section |
| `get_related_sections` | Sections related to a topic or another section |

| Use **the book server** when… | Use a sibling when… |
|---|---|
| You want **textbook** theory, definitions, and worked examples — cited. | You want to **run** a model conversationally → the [`ksl` MCP server](mcp-server.md) |
| You're studying, doing homework, or reviewing a concept. | You're **writing KSL code** and want the real API → the [`ksl-code` server](mcp-server-code.md) |

---

## 2. Before you begin

**Check Java 21.**

```bash
java -version      # must report 21.x
```

**Get the jar.** For a course, download `ksl-book-mcp.jar` from the link your instructor
provides and skip to [§3](#3-connect-your-ai-client). To build it yourself from the KSL
repository (requires JDK 21) — it is a module of the KSL root build:

```bash
./gradlew :KSLBookServer:shadowJar
# → KSLBookServer/build/libs/ksl-book-mcp.jar
```

> **Building needs the rendered book.** The build reads a rendered copy of the book from
> the repo-root `_book/` directory (the `quarto render` output, git-ignored). If `_book/`
> is absent the build still succeeds but bundles **empty** content — so a course jar is
> normally built and released by hand from a fresh render (see the module's
> `RELEASING.md`). Most students should just download the released jar.

**Verify the jar** with the built-in self-test — it prints the server version and content
stats:

```bash
java -jar KSLBookServer/build/libs/ksl-book-mcp.jar --doctor
```

```text
KSL Book MCP server - doctor
  server version: 1.0.0
  chapters:       14
  sections:       196
  exercises:      212
  OK - the server runs and the textbook content is loaded.
```

If `chapters`/`sections` read `0`, the jar was built without a rendered `_book/` — get a
released jar or rebuild from a render.

---

## 3. Connect your AI client

With stdio, **your client launches the server** as a subprocess — you just register it in
the client's MCP configuration.

**The easy way — let the server wire itself in.** Double-click the jar, or run the setup
window:

```bash
java -jar ksl-book-mcp.jar --gui      # setup window (also: double-click the jar)
java -jar ksl-book-mcp.jar --setup    # or wire detected agents from the console
```

It detects installed agents (Claude Desktop, Codex), merges in a `ksl-book` entry
(backing up the original config once, as `*.ksl-book-backup`), and — if it finds none —
prints the exact snippet to paste yourself.

**The manual way.** Add this under `mcpServers` in your client's config (for Claude
Desktop, `claude_desktop_config.json` — macOS `~/Library/Application Support/Claude/`,
Windows `%APPDATA%\Claude\`, Linux `~/.config/Claude/`):

```json
{
  "mcpServers": {
    "ksl-book": { "command": "java", "args": ["-jar", "/absolute/path/to/ksl-book-mcp.jar", "--stdio"] }
  }
}
```

> **Use absolute paths.** A GUI client doesn't inherit your shell `PATH`, so use the full
> path to `java` and an absolute jar path.

**Restart the client fully.** The `ksl-book` server then appears in the client's tools
list (in Claude Desktop, under Settings → Connectors), alongside `ksl` and `ksl-code` if
you installed those too. Verify by asking:

> *"Use the ksl-book tools — list the chapters of the textbook."*

The assistant should call `list_chapters` and report the book's chapters. You're
connected.

### Getting the assistant to actually use the book

The server tells the client during connection to search this textbook before web search
or general knowledge for any simulation or course question — so usually no action is
needed. If routing still feels lazy, a Claude Desktop **Project** for the course with a
custom instruction makes it airtight:

> Questions in this project concern my simulation course, which uses the KSL textbook.
> Always search the textbook first (ksl-book tools) and cite section URLs in your answers.

---

## 4. Tutorial — your first textbook session

With the client connected, here is a real first session. You ask course questions in
plain language; the assistant calls the textbook tools and cites what it finds. The tool
calls and outputs below are the protocol exchanges.

### Step 1 — Ask a concept question

> *"What is a warm-up period, and why does it matter?"*

The assistant calls `search_textbook`, which returns ranked sections:

```text
search_textbook("warm up period initial conditions") — top matches:
  1. §9.5  Initialization Bias and the Warm-Up Period      (rossetti.github.io/KSLBook/…#sec-warmup)
  2. §9.5.1 The Welch Method for Selecting a Warm-Up Point
  3. §9.4  Infinite-Horizon (Steady-State) Simulation
```

### Step 2 — Read the section

> *"Summarize section 9.5 and cite it."*

`get_section` returns the full text of that section with its citation URL, so the
assistant can summarize the real book and link you to it — not paraphrase from memory.

### Step 3 — Practice with the exercises

> *"Give me the exercises for chapter 9 on this."*

`get_exercises` returns that chapter's end-of-chapter problems, so you can study or check
your homework against the actual book. `get_related_sections` then points you to adjacent
material.

### Reading the results

The point of the book server is **fidelity**: the assistant answers from the actual
textbook your course uses and cites section URLs you can open, rather than approximating
from general knowledge. Because the content is baked into the jar at build time, the jar
must be rebuilt when the book changes — ask for `--doctor` if you're unsure how current a
jar is.

---

## 5. Reference

### The tools

`search_textbook` (start here) · `get_section` · `get_chapter_outline` · `list_chapters`
· `get_exercises` · `get_related_sections`.

`search_textbook` is the entry point; the rest drill in. `get_chapter_outline` and
`list_chapters` orient you; `get_section` pulls full text; `get_exercises` returns
problems; `get_related_sections` finds adjacent material.

### Launcher modes

| Mode | Purpose |
|---|---|
| `ksl-book-mcp.jar --stdio` | the MCP server an AI client runs (JSON-RPC on stdout; logs to stderr) |
| `ksl-book-mcp.jar --doctor` | self-test: server version, chapter / section / exercise counts |
| `ksl-book-mcp.jar --gui` | setup window (also: double-click the jar) |
| `ksl-book-mcp.jar --setup` / `--remove` | wire / unwire the server into detected clients |
| `ksl-book-mcp.jar --version` | print the server version |

### Keeping content current

The book text and citation URLs are baked into the jar at build time from the rendered
`_book/`, so a jar reflects the book as of its build. When the book is revised, the jar is
re-rendered and rebuilt (an instructor/release step — see the module's `RELEASING.md`).
Citation URLs resolve in a browser once the matching render is published to the book's
site.

---

## 6. Common tasks

| Task | How |
|---|---|
| See what's loaded | run `--doctor` (chapter / section / exercise counts) |
| List the chapters | ask the assistant to call `list_chapters` |
| Force textbook-first answers | add the project instruction in [§3](#getting-the-assistant-to-actually-use-the-book) |
| Re-wire / remove a client | `ksl-book-mcp.jar --setup` / `--remove` |
| Get a newer book | download the rebuilt jar (or re-render `_book/` and rebuild `shadowJar`) |

---

## 7. Troubleshooting & gotchas

| Symptom | Cause | Fix |
|---|---|---|
| `ksl-book` doesn't appear after `--setup` | The client wasn't fully restarted, or the config path is wrong. | Quit and reopen the client; confirm the `ksl-book` entry is in the right `mcpServers` config file. |
| `--doctor` reports 0 chapters / sections | The jar was built without a rendered `_book/`. | Use a released jar, or render the book into `_book/` and rebuild `shadowJar`. |
| The assistant answers from general knowledge, not the book | It didn't route to the tools. | Ask explicitly: *"use the ksl-book tools to…"*, or add the project instruction in [§3](#getting-the-assistant-to-actually-use-the-book). |
| Citation links don't open | The matching book render isn't published yet, or the jar is ahead of the site. | Use the section number; links resolve once the render is published. |
| The client shows garbled output / protocol errors | Something wrote to **stdout**, the MCP channel. | In `--stdio` mode only JSON-RPC goes to stdout; logging goes to stderr — don't redirect logs to stdout. |
| `UnsupportedClassVersion` on launch | Wrong Java. | Use JDK 21 (`java -version`). |

---

## 8. See also

- [MCP Server](mcp-server.md) — the `ksl` server that **runs** models for an assistant.
- [Code MCP Server](mcp-server-code.md) — the `ksl-code` server for **KSL source / API**.
- [KSL Book](https://rossetti.github.io/KSLBook/) — the textbook this server indexes.
