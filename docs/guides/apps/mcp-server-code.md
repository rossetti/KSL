# KSL Code MCP Server — User Guide

The **KSL Code MCP server** gives any MCP-capable AI assistant (Claude Desktop,
Cursor, Codex, …) **searchable access to the KSL source code** — the public API of
`KSLCore` and the worked programs in `KSLExamples`. Ask your assistant a coding
question ("how do I seize and release a resource?", "what implements `SpatialModel`?")
and it searches the real KSL declarations, KDoc, and examples instead of guessing from
memory. The `.kt` source is parsed at build time into a search index and bundled into a
single self-contained jar.

> **You will need:** Java 21 on your `PATH` and an **MCP-capable client** (this guide
> uses Claude Desktop). You'll need the `ksl-code-mcp.jar` — download it from your
> course link, or build it ([§2](#2-before-you-begin)). Unlike the desktop-app guides,
> this is a *server* your AI client drives, so the examples are **real command and tool
> transcripts**, not screenshots.

## What you'll be able to do

- Install `ksl-code-mcp.jar` and wire it into your AI client — automatically or by hand.
- Verify the connection and see the code-search tools the assistant gains.
- Have the assistant answer KSL coding questions from the actual API and examples.
- Look up a class's full signature, KDoc, members, and the examples that use it.
- Know which KSL git ref the answers correspond to, and how to pin it for a course.

---

## 1. At a glance

This is one of **three** separate KSL student MCP servers. Each answers a different
kind of question, each runs as its own JVM process, and your assistant routes to the
right one by context — no per-question configuration:

| Server | Answers questions about | Guide |
|---|---|---|
| `ksl` (KSLServerMcp) | **Running** models — single runs, experiments, optimization, distribution fitting | [MCP Server](mcp-server.md) |
| `ksl-book` (KSLBookServer) | **Textbook** concepts, theory, worked examples from the book | [Book MCP Server](mcp-server-book.md) |
| **`ksl-code`** (this server) | **Source code** — API declarations, KDoc, structure, code examples | this guide |

You never call the tools yourself — the assistant does, in response to what you ask.
The tools it gains:

| Tool | What it's for |
|---|---|
| `search_code` | Full-text search over the KSL API and examples — the entry point |
| `get_class` | Full API of one declaration: signature, supertypes, KDoc, members, examples |
| `get_example` | The `KSLExamples` files that use a given declaration |
| `get_package_overview` | Every public declaration in a package, with one-line summaries |
| `find_subclasses` | Declarations that extend / implement a given type |
| `get_related_examples` | `KSLExamples` programs related to a topic |
| `list_modules` | Modules, declaration counts, and package lists |
| `get_server_info` | Which KSL ref is indexed, the build date, the declaration count |

| Use **the code server** when… | Use a sibling when… |
|---|---|
| You're **writing KSL code** and want the assistant grounded in the real API. | You want to **run** a model conversationally → the [`ksl` MCP server](mcp-server.md) |
| You want exact signatures, KDoc, and example usages — not hallucinated ones. | You want **textbook** theory and concepts → the [`ksl-book` server](mcp-server-book.md) |

---

## 2. Before you begin

**Check Java 21.**

```bash
java -version      # must report 21.x
```

**You already have the server.** Installing the KSL suite puts it here, next to the other
servers — there is nothing to download or build:

| Platform | Location |
|---|---|
| macOS / Linux | `~/Applications/KSL/.support/Servers/code/` |
| Windows | `%LOCALAPPDATA%\Programs\KSL\.support\Servers\code\` |

Not installed yet? It's one command — see the [installation guide](install.md).
(`.support` is hidden in a file browser on purpose; it holds the plumbing. You only need
its path for a command like the one below.)

The server carries a **search index of the KSL source, built into the jar** when the suite
was assembled — so it answers instantly and indexes nothing on your machine.

**Verify it** with the built-in self-test — it prints the server version and what's
indexed:

```bash
~/Applications/KSL/.support/Servers/code/ksl-code-mcp --doctor
```

```text
KSL Code MCP server - doctor
  version:      1.0.0
  KSL ref:      develop  (indexed 2026-07-15)
  declarations: 4041
  by module:    {KSLCore=2972, KSLExamples=1069}
  OK - the server runs and the KSL source index is bundled.
```

(The ref and counts are whatever your suite was built from.)

---

## 3. Connect your AI client

With stdio, **your client launches the server** as a subprocess — you just register it
in the client's MCP configuration.

**The easy way — let the server wire itself in.** Run the setup window (or `--setup` to do
it from the console):

```bash
cd ~/Applications/KSL/.support/Servers/code
./ksl-code-mcp --gui        # setup window
./ksl-code-mcp --setup      # or wire detected agents from the console
```

It detects installed agents (Claude Desktop, Codex), merges in a `ksl-code` entry
(backing up the original config once, as `*.ksl-code-backup`), and — if it finds none —
prints the exact snippet to paste yourself.

**The manual way.** Add this under `mcpServers` in your client's config (for Claude
Desktop, `claude_desktop_config.json` — macOS `~/Library/Application Support/Claude/`,
Windows `%APPDATA%\Claude\`, Linux `~/.config/Claude/`):

```json
{
  "mcpServers": {
    "ksl-code": {
      "command": "/absolute/path/to/java",
      "args": ["-jar", "/Users/you/Applications/KSL/.support/Servers/code/ksl-code-mcp.jar", "--stdio"]
    }
  }
}
```

`ksl-code-mcp.jar` is self-contained, so `java -jar` starts it directly. (The `ksl` model
server is different — it shares the suite's libraries, so its config points at its wrapper
script instead. Its `--setup` handles that for you.)

> **Use absolute paths.** A GUI client doesn't inherit your shell `PATH`, so use the full
> path to `java` and an absolute jar path. The `--setup` output uses `java.home/bin/java`
> for exactly this reason.

**Restart the client fully.** The `ksl-code` server then appears in the client's tools
list (in Claude Desktop, under Settings → Connectors), alongside `ksl` and `ksl-book` if
you installed those too. Verify by asking:

> *"Use the ksl-code tools — what KSL modules are indexed?"*

The assistant should call `list_modules` and report `KSLCore` and `KSLExamples` with
declaration counts. You're connected.

---

## 4. Tutorial — your first code-search session

With the client connected, here is a real first session. You ask coding questions in
plain language; the assistant calls the code-search tools and grounds its answer in the
actual source. The tool calls and outputs below are the protocol exchanges.

### Step 1 — Ask a "how do I…" question

> *"In KSL, how do I seize and release a resource in a process model?"*

The assistant calls `search_code` with your phrasing, which returns ranked declarations
and examples:

```text
search_code("seize release a resource") — top matches:
  1. ksl.modeling.entity.ResourceWithQ        (class)     — a Resource bundled with a request queue
  2. ksl.modeling.entity.KSLProcessBuilder    (interface) — seize / release / delay verbs for a process
  3. ksl.modeling.entity.Resource             (class)     — a seizable unit-capacity resource
  example: KSLExamples … book/chapter7/Ch7Example1.kt  (seizes a ResourceWithQ inside process { })
```

### Step 2 — Look up the exact API

> *"Show me the full API of `ResourceWithQ`."*

The assistant calls `get_class("ksl.modeling.entity.ResourceWithQ")`, which returns the
signature, supertypes, KDoc, and members — the real declaration, not a guess:

```text
class ResourceWithQ(parent: ModelElement, name: String? = null, capacity: Int = 1, queue: RequestQ? = null) : Resource
  KDoc: A Resource that has an internal RequestQ so waiting requests queue automatically.
  key members:
    val waitingQ: QueueCIfc              — the bundled request queue (all standard queue statistics)
    val numBusyUnits: TWResponseCIfc     — allocated units over time
    val numActiveUnits: TWResponseCIfc   — currently-active capacity
  used by 9 KSLExamples programs (get_example for the list)
```

### Step 3 — See it used in a real program

> *"Show me an example that uses it."*

`get_example("ksl.modeling.entity.ResourceWithQ")` lists the `KSLExamples` files that
seize it, with citation URLs pinned to the indexed KSL ref, so the assistant can quote
working code rather than invent it.

### Reading the results

The point of the code server is **grounding**: every class name, signature, and example
the assistant shows comes from the indexed source at a known KSL ref — so the code it
writes for you compiles against the library you actually have. If an answer looks stale,
ask it to call `get_server_info` and check the **KSL ref** (below).

---

## 5. Reference

### The tools

`search_code` (start here) · `get_class` · `get_example` · `get_package_overview` ·
`find_subclasses` · `get_related_examples` · `list_modules` · `get_server_info`.

`search_code` is the entry point; the rest drill in. `find_subclasses` answers "what
implements `SpatialModel`?"; `get_package_overview` lists a whole package;
`get_related_examples` finds programs for a topic.

### Launcher modes

Run these from the server's folder (`.support/Servers/code/`):

| Mode | Purpose |
|---|---|
| `ksl-code-mcp --stdio` | the MCP server an AI client runs (JSON-RPC on stdout; logs to stderr) |
| `ksl-code-mcp --doctor` | self-test: server version, indexed KSL ref, declaration count |
| `ksl-code-mcp --gui` | setup window |
| `ksl-code-mcp --setup` / `--remove` | wire / unwire the server into detected clients |
| `ksl-code-mcp --version` | print the server version |

### Which KSL version am I searching?

The bundled index corresponds to one KSL **git ref**, used in the citation URLs and
reported by `get_server_info` (and `--doctor`). It is fixed when the suite is assembled, so
every server in your installed suite searches the same ref — ask the assistant to call
`get_server_info` when you need to know which one. Updating the suite brings a
freshly-indexed server with it.

---

## 6. Common tasks

| Task | How |
|---|---|
| See what's indexed | ask the assistant to call `list_modules`, or run `--doctor` |
| Check the indexed KSL ref | ask it to call `get_server_info` (or `--doctor`) |
| Re-wire / remove a client | `ksl-code-mcp --setup` / `--remove` |
| Pick up a newer KSL index | update the suite: `~/Applications/KSL/bin/ksl update` |
| Ground the assistant in the API | just ask KSL coding questions — it routes to `search_code` |

---

## 7. Troubleshooting & gotchas

| Symptom | Cause | Fix |
|---|---|---|
| `ksl-code` doesn't appear after `--setup` | The client wasn't fully restarted, or the config path is wrong. | Quit and reopen the client; confirm the `ksl-code` entry is in the right `mcpServers` config file. |
| Answers cite an old API | The suite was built against an older KSL ref. | Check `get_server_info`; update the suite (`ksl update`). |
| The client shows garbled output / protocol errors | Something wrote to **stdout**, which is the MCP channel. | In `--stdio` mode only JSON-RPC goes to stdout; all logging goes to stderr — don't redirect logs to stdout. |
| `UnsupportedClassVersion` on launch | Wrong Java. | Use JDK 21 (`java -version`). |
| The assistant answers from memory instead of searching | It didn't route to the tools. | Ask explicitly: *"use the ksl-code tools to…"*, or add a project instruction to search KSL code first. |

---

## 8. See also

- [MCP Server](mcp-server.md) — the `ksl` server that **runs** models for an assistant.
- [Book MCP Server](mcp-server-book.md) — the `ksl-book` server for **textbook** concepts.
- The KSL library guides — [`ksl-entity`](../ksl-entity.md), [`ksl-modeling`](../ksl-modeling.md),
  and the rest under [`../README.md`](../README.md) — the same API, written for a human reader.
- [KSL Book](https://rossetti.github.io/KSLBook/) — the simulation concepts behind the code.
