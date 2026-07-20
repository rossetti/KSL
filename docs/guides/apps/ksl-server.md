# KSL Server — User Guide

The **KSL Server** turns any MCP-capable AI assistant (Claude Desktop, Cursor, Codex, …) into a
front-end for KSL. It is **one long-running server** that exposes all three KSL tool
surfaces on a single endpoint:

- **run and analyze simulation models** — single runs, scenario comparisons, designed
  experiments, simulation-optimization, and distribution fitting;
- **search the KSL textbook** — sections, chapter outlines, and exercises, with cited URLs;
- **search the KSL source code** — the public API and the worked examples.

You talk to the assistant in plain language; it calls the tools. You run **one** server: start
it from a **menu-bar / system-tray app**, connect your client with **one click**, and manage
everything from a **web console** in your browser. No JSON to edit, and one warm server is
shared by every client.

> **You will need:** the KSL suite installed (see [Install the KSL suite](install.md)) and an
> **MCP-capable client** (this guide uses Claude Desktop). That's it — the server ships with
> the suite, already knows the example models, and wires itself into your client. Unlike the
> desktop-app guides, this is a *server* your assistant drives, so the examples below are
> **real tool interactions**, not screenshots.

## What you'll be able to do

- Start the KSL Server from a menu-bar / system-tray icon, and open its web console.
- Connect your AI assistant with one click — no config files to edit.
- Ask the assistant to run models, search the textbook, and search the KSL source.
- Turn individual tool surfaces on or off.
- See live activity, and hand your instructor a usage-study file (or opt out entirely).
- Run the server headless on a machine with no display.

---

## 1. At a glance

The KSL Server is a single background service. You don't open a window and work in it; you
**start it and leave it running**, the way you would Postgres.app or Docker Desktop. A
menu-bar icon (macOS) / system-tray icon (Windows) shows whether it's up, and a browser
console does the detailed work. Your AI assistant reaches it through a tiny **bridge** that
the console configures for you.

**One server, three surfaces** — the tools group by capability:

| Surface | What it's for | Representative tools |
|---|---|---|
| **sim** | run and analyze models — runs, scenarios, experiments, optimization, fitting | `run_model`, `run_experiment`, `run_optimization`, `fit_dataset`, `get_started` |
| **book** | search the KSL textbook and cite it | `search_textbook`, `get_section`, `get_chapter_outline`, `list_chapters`, `get_exercises` |
| **code** | search the KSL source code and API | `search_code`, `get_class`, `get_example`, `find_subclasses`, `get_package_overview` |

Your assistant discovers every tool the server exposes and picks the right one from plain
language — ask it to run a model, compare scenarios, design an experiment, optimize inputs, or
fit a distribution. `get_started` gives it the live model catalog and a suggested workflow.

Use the KSL Server when you want an **AI assistant** to run models and answer course and code
questions from the real book and source. Prefer a hands-on GUI? The [desktop apps](README.md)
(Single, Scenario, …) reach the same KSL capabilities.

---

## 2. Before you begin

**Install the suite.** The KSL Server ships with it — there is nothing separate to download
or build. One command installs everything:

```
curl -fsSL https://raw.githubusercontent.com/rossetti/KSL/main/install.sh | bash
```

See the [installation guide](install.md) for Windows, for installing from a local
`ksl-suite.zip`, and for the `KSLWork` folder layout. The installer creates a
double-clickable **KSL Server** entry point, next to the other apps:

| Platform | Where |
|---|---|
| macOS | **Launchpad → KSL Server** (also `~/Applications/KSL/KSL Server.app`) |
| Windows | **Start Menu → KSL → KSL Server** |
| Linux | **KSL Server** in your applications menu |

**You already have models.** Like the desktop apps, the server serves the shipped **KSL Book
Examples** and **KSL Animation Examples** bundles, so the assistant has something to run the
moment you connect. Drop your own bundle JAR into `<KSLWork>/bundles/` (or the server's
`<KSLWork>/KSLServer/bundles/`) and it is picked up within a few seconds — no restart. See
[Bundle Tools](bundle-tools.md) to build your own.

---

## 3. Quick start — from install to your first tool call

Six steps, once. After this, the assistant just has the KSL tools.

### Step 1 — Open KSL Server

Launch **KSL Server** (see the table above). Nothing opens a window — instead a small **status
lamp** appears in your **menu bar** (macOS, top-right) or **system tray** (Windows, bottom-right).
The lamp is the server's status at a glance:

| Lamp | Meaning |
|---|---|
| **Green** | running — ready for clients |
| **Amber** | starting up (a second or two) |
| **Gray** | stopped |

Opening KSL Server starts the server as a managed background process, so the lamp goes
amber, then green. Quitting it (below) stops the server again.

### Step 2 — Open the console

Click the lamp to open its menu:

- **● Running** — a status line (disabled; it just reports the state)
- **Open Console** — opens the web console in your browser
- **Start at login** — optional; keeps the server running across reboots
- **Quit** — stops the server and removes the icon

Choose **Open Console**. Your browser opens the console at:

```
http://127.0.0.1:3001/admin
```

(Double-clicking the lamp opens the same page.) The console header should show a green
**RUNNING** lamp and the version.

### Step 3 — Connect your assistant

In the console's **Clients** region (the first one), click **Connect**. This:

- auto-detects the bundled bridge and your installed assistant (Claude Desktop, Cursor,
  Windsurf, or Codex);
- writes a **single** `ksl-suite` entry into that client's MCP configuration — **no JSON
  editing**;
- points it at the running server.

That's the whole setup. If no assistant is found, the console says so — install one of those
clients first, then click **Connect** again. Using a different MCP client (VS Code, Cline, …)?
See [Connecting a different MCP client](#connecting-a-different-mcp-client) below.

### Step 4 — Restart the assistant

MCP clients read their server list at startup, so **fully quit and reopen** your assistant.
The KSL tools appear after the restart (in Claude Desktop, under Settings → Connectors, as
**ksl-suite**).

### Step 5 — Make your first tool call

Ask the assistant something that exercises the new textbook surface:

> *"Use the ksl tools to search the textbook for event scheduling."*

The assistant calls `search_textbook`, and reports the matching sections with their book
URLs. Try the others too:

> *"List the available models and run the DriveThroughPharmacyWithQ for 30 replications."*
> *"Search the KSL source for how to seize and release a resource."*

You're connected. From here, just ask in plain language — run a model and read the result
table, compare scenarios, design an experiment, optimize inputs, or fit a distribution; the
assistant calls the right tool. Ask it to *"get started"* for the live model catalog and a
suggested workflow.

### Step 6 — Leave it running (or quit)

The server keeps running in the background, warm and shared, until you **Quit** it from the
menu (or reboot, unless you enabled **Start at login**). You don't need to reconnect the
client again — the `ksl-suite` entry stays.

### Connecting a different MCP client

**Connect** auto-configures Claude Desktop, Cursor, Windsurf, and Codex. Any **other** MCP
client works too — the KSL Server is a standard MCP server, and the bundled **`ksl-bridge`** is
a standard stdio MCP server. Add one entry to that client's own MCP config, pointing at the
bridge:

- **command** — `~/Applications/KSL/.support/Servers/suite/ksl-bridge` (Windows: the
  `ksl-bridge.cmd` in that folder, launched via `cmd.exe /c`)
- **args** — `--url http://127.0.0.1:3001/`
- **name** — `ksl-suite`

Most clients use the same `mcpServers` JSON that Claude Desktop does — just in that client's
own config file or UI:

```json
{
  "mcpServers": {
    "ksl-suite": {
      "command": "/Users/you/Applications/KSL/.support/Servers/suite/ksl-bridge",
      "args": ["--url", "http://127.0.0.1:3001/"]
    }
  }
}
```

A client that speaks **HTTP/SSE MCP directly** can skip the bridge and point at the server's
endpoint, `http://127.0.0.1:3001/`. Either way, restart the client (Step 4) and the KSL tools
appear.

---

## 4. The web console, region by region

The console (**http://127.0.0.1:3001/admin**) is server-rendered and updates live — a tool
call from any client appears without a page reload. Its regions, top to bottom:

1. **Status header** — a **RUNNING** lamp, the suite version, and a running count of tool
   calls served. It reads **DEGRADED** (amber) if a surface is enabled but not yet ready.
2. **Clients** — the setup surface from Step 3. **Connect** / **Disconnect** write or remove
   the one `ksl-suite` entry in your assistant's config. A reminder appears once connected:
   restart the assistant so it loads the tools.
3. **Capabilities** — the three surfaces (**sim**, **book**, **code**), each with an
   enable toggle, a readiness indicator, and its call count. See [§5](#5-enabling-only-some-capabilities).
4. **Activity & usage** — a live feed of the current run's recent tool calls, a bar chart of
   the most-used tools, a **Refresh** button, and a **CSV** export. This view is the current
   run only; the durable record is the usage-study log ([§6](#6-the-usage-study-and-opting-out)).
5. **Usage study** — the recording control, its plain-language disclosure, the log-file path,
   and the hand-off exports. See [§6](#6-the-usage-study-and-opting-out).
6. **Diagnostics** — a copy-paste summary (version + capabilities) for a bug report, and a
   pointer to the log folder, `~/.ksl/logs`.

The action buttons (Connect, capability toggles, usage controls, Show file) work only when
the console is opened on the **server's own machine** — they are local-only by design.

---

## 5. Enabling only some capabilities

All three surfaces are on by default. To run a subset — say, textbook search only for a
course that isn't doing modeling yet — use the console's **Capabilities** region:

1. Untick the surfaces you don't want (e.g. leave **book** ticked, untick **sim** and **code**).
2. Click **Apply & Restart**. This saves the choice; it does **not** restart the server for you.
3. From the menu-bar icon, choose **Quit**, then reopen **KSL Server**. The server comes back
   up serving only the enabled surfaces.

Disabling **sim** also skips loading the bundle registry and run services, so a textbook-only
server starts light.

You can also set this outside the console — in `~/.ksl/config.toml`:

```toml
[capabilities]
sim = false
book = true
code = false
```

or with environment variables when launching the server: `KSL_CAPABILITY_SIM`,
`KSL_CAPABILITY_BOOK`, `KSL_CAPABILITY_CODE` (each `true`/`false`; an env var overrides the
file).

---

## 6. The usage study (and opting out)

The server can keep a **local** record of which tools get used — for a professor studying how
students work. It is stored **only on your machine**; **nothing is ever transmitted off it**.

**The control** is in the console's **Usage study** region, with three levels:

| Level | What it records |
|---|---|
| **Full** (default) | one line per tool call, **including** search text and a compact, PII-free run digest |
| **Counts (no text)** | the same, but **without** any free text (query / digest / error summary dropped) |
| **Off** | nothing at all — the opt-out |

Changing the level takes effect immediately and is remembered across restarts. The region
also shows a plain-language description of what's being recorded and the log-file path.

**Where it lives.** One append-only file, `usage.jsonl`, under the server's app folder
(default `<KSLWork>/KSLServer/usage/`; change it with `[usage] dir` in `config.toml` or
`KSL_USAGE_DIR`). Each line records: tool, capability, timing, ok/error, client, session id,
error class/summary, target, result count, top score, query, params digest, and intent — the
free-text fields only at **Full**.

**Handing it to your instructor.** The Usage study region has three buttons:

- **Export data (.jsonl)** — the full-fidelity log, every field, one JSON object per call.
- **Export (.csv)** — the same data as a spreadsheet, all 15 columns:
  `timestampMillis, capability, tool, durationMs, ok, client, sessionId, errorClass,
  errorSummary, target, resultCount, topScore, query, paramsDigest, intent`.
- **Show file** — opens the folder holding the log, so you can attach the file yourself.

Export filenames are `ksl-usage[-<label>]-<date>.<ext>`, where `<label>` is an optional
per-student tag set with `[usage] label` in `config.toml` (handy for attribution when a class
hands files in). The server never analyzes or uploads the file — you hand it over.

---

## 7. Running headless

On a machine with no display (a lab server, an SSH session), there is no menu bar to click.
Run the server process directly instead:

```
~/Applications/KSL/.support/Servers/suite/ksl-suite
```

(Windows: `%LOCALAPPDATA%\Programs\KSL\.support\Servers\suite\ksl-suite.cmd`.) It stays up
until you stop it (Ctrl-C), serving the same MCP endpoint and console on
`http://127.0.0.1:3001`. `.support` is hidden in a file browser on purpose; you only need its
path for this command.

To reach a headless server from another machine — or to require a token — it binds
`127.0.0.1` with no authentication by default. Set `KSL_BIND_HOST=0.0.0.0` and
`KSL_AUTH_TOKEN=<secret>` (clients then send `Authorization: Bearer <secret>`); the server
has **no TLS**, so put it behind an SSH tunnel or a reverse proxy. Never expose it
unauthenticated.

---

## 8. Reference

**Console:** `http://127.0.0.1:3001/admin` (the menu's **Open Console**).

**What ships**, in the suite's `Servers/suite/` folder
(`~/Applications/KSL/.support/Servers/suite/`):

| Piece | What it is |
|---|---|
| `ksl-suite` | the server itself — the long-running MCP + console process (run directly when headless) |
| `ksl-server` | the menu-bar / tray agent behind **KSL Server** — starts `ksl-suite` and shows the lamp |
| `ksl-bridge` | the thin stdio↔HTTP bridge each client launches; forwards to the one running server |

**Suite command-line flags** (`ksl-suite …`):

| Flag | What it does |
|---|---|
| `--version` | print the server version and exit |
| `--configure` | write the `ksl-suite` entry into detected clients (the console's **Connect**) |
| `--remove` | remove the `ksl-suite` entry from detected clients (the console's **Disconnect**) |

**Key configuration** — environment variable (highest priority), then `~/.ksl/config.toml`,
then the default:

| Env var | Config | Default | What it does |
|---|---|---|---|
| `KSL_MCP_PORT` | `server.mcpPort` | `3001` | the server / console port |
| `KSL_CAPABILITY_{SIM,BOOK,CODE}` | `[capabilities]` | all `true` | which surfaces are served |
| `KSL_USAGE_DETAIL` | `usage.detail` | `full` | usage-study level (`off`/`counts`/`full`) |
| `KSL_USAGE_DIR` | `usage.dir` | `<KSLWork>/KSLServer/usage` | where `usage.jsonl` is written |
| `KSL_BUNDLES_DIR` | `bundles.dir` | `<KSLWork>/KSLServer/bundles` + `<KSLWork>/bundles` + shipped examples | where the server looks for model bundles |
| `KSL_BIND_HOST` | `server.bindHost` | `127.0.0.1` | HTTP bind address (`0.0.0.0` to expose on a LAN) |
| `KSL_AUTH_TOKEN` | `server.authToken` | *(none)* | when set, HTTP requires `Authorization: Bearer <token>` |

---

## 9. Troubleshooting & gotchas

| Symptom | Cause | Fix |
|---|---|---|
| The lamp never turns green (stays amber or gray) | The server failed to start. | Run `~/Applications/KSL/.support/Servers/suite/ksl-suite` in a terminal to see the error; check `~/.ksl/logs`. Confirm Java 21 with `java -version`. |
| **Connect** says no assistant found | None of the auto-detected clients (Claude Desktop, Cursor, Windsurf, Codex) is installed, or its config folder doesn't exist yet. | Install and launch one once, then click **Connect** again — or connect another client by hand (see [Connecting a different MCP client](#connecting-a-different-mcp-client)). |
| KSL tools don't appear after **Connect** | The client wasn't fully restarted. | Quit the assistant completely and reopen it; the `ksl-suite` tools then load. |
| Port `3001` already in use | Another program (or a second KSL Server) holds the port. | Quit the other user, or run the server on another port: set `KSL_MCP_PORT` and open the console at that port. |
| The console shows a red **"Server stopped — this page is stale"** banner | You quit the server while the console page was open. | Reopen **KSL Server**, then reload the console page. |
| **Open Console** is greyed out | The server isn't running yet. | Wait for the lamp to turn green, then try again. |
| The assistant sees no models | No bundle in the watched folders. | Drop a bundle JAR into `<KSLWork>/bundles/`; it's picked up within seconds. See [Bundle Tools](bundle-tools.md). |

---

## 10. See also

- [Installing the KSL Applications](install.md) — install, update, and uninstall the suite.
- [Common UI & concepts](common-ui.md) — models & bundles, the workspace, reports.
- The desktop app guides — [Single](single.md), [Scenario](scenario.md),
  [Experiment](experiment.md), [Simopt](simopt.md), [Results](results.md),
  [Distribution](distribution.md) — the GUI way to reach the same KSL capabilities.
- [KSL Book](https://rossetti.github.io/KSLBook/) — the simulation concepts behind the models
  and the statistics the assistant reports.
