# KSL MCP Server — User Guide

The **KSL MCP server** turns any MCP-capable AI assistant (Claude Desktop, Cursor,
Codex, …) into a front-end for KSL: the assistant **calls tools** to run and analyze
your simulation models — single runs, scenario comparisons, designed experiments,
simulation-optimization — and to fit probability distributions and generate random
variates. You talk to the assistant in plain language; it drives KSL for you.

> **You will need:** Java 21 on your `PATH`, a terminal, and an **MCP-capable client**
> (this guide uses Claude Desktop). You'll build the server jar and an example model
> bundle below. Unlike the desktop-app guides, this one is a *server* an AI client
> drives, so the examples are **real command and tool transcripts**, not screenshots.
>
> New to model bundles? See [Bundle Tools](bundle-tools.md). This guide covers a
> local, single-user setup; [§3](#3-connect-your-ai-client) has a short note on
> exposing the server over a network.

## What you'll be able to do

- Build and launch the KSL MCP server.
- Wire it into your AI client — automatically or by hand.
- Verify the connection and see the tools the assistant gains.
- Have the assistant run the example pharmacy model and read the results with you.
- Serve **your own** model by dropping in a bundle JAR.
- Know when to reach for stdio vs. HTTP, and where to go for security and operations.

---

## 1. At a glance

The server exposes KSL as **~68 tools** plus **7 guided prompts**, over either of two
transports. You never call the tools yourself — your assistant does, in response to
what you ask it.

**Two transports:**

| Transport | Best for | How it runs | Endpoint |
|---|---|---|---|
| **stdio** | one AI client on your machine (Claude Desktop, Cursor, Codex) | the **client launches** the server as a subprocess | none — stdin/stdout |
| **HTTP / SSE** | a standing server, or remote / multiple clients | **you run it**; it stays up | `http://127.0.0.1:3001` |

Most people want **stdio** — skip to [§3](#3-connect-your-ai-client). HTTP is
summarized there too.

**What the assistant can do** — the tools group into capability areas:

| Area | What it's for | Representative tools |
|---|---|---|
| **Orientation** | the turn-one router | `get_started` |
| **Discovery** | what models exist and their inputs/outputs | `list_bundles`, `describe_model` |
| **Run** | run one model (blocking or async) | `run_model`, `run_config`, `submit_run`, `get_run_result` |
| **Experiments** | two-level factorial designs | `run_experiment`, `experiment_config` |
| **Optimization** | search inputs for the best response | `run_optimization` |
| **Distribution fitting** | fit distributions to data | `fit_dataset`, `get_fit_report` |
| **Data & statistics** | summaries, histograms, autocorrelation | `summarize_data`, `acf_analysis` |
| **Random variates** | sample from a distribution | `list_distributions`, `generate_variates` |
| **Results & artifacts** | fetch retained results and reports | `get_result`, `list_results`, `get_artifacts` |
| **Database analysis** | mine a run's KSL database | `db_summary`, `db_compare` |
| **Animation & layout** | auto-derive / validate / render a layout | `auto_layout`, `render_animation_layout` |
| **Bundle authoring** | package a builders JAR into a bundle | `bundle_authoring_candidates`, `assemble_bundle` |
| **Documents** | save/reuse configs across sessions | `save_document`, `load_document` |
| **Workspace** | where the server reads and writes | `get_workspace`, `set_workspace` |

The full tool list is in [§5](#5-reference). When in doubt, ask the assistant to call
`get_started` — it returns the live model catalog and routes you to the right workflow.

| Use **the MCP server** when… | Use a sibling when… |
|---|---|
| You want an **AI assistant** to run and explain models conversationally. | You want a hands-on GUI → the [desktop apps](README.md) (Single, Scenario, …) |
| You're exploring, and want the assistant to pick the right KSL workflow. | You want to script runs from a shell or web app → the **REST** server (a sibling transport) |

---

## 2. Before you begin

**Check Java 21.**

```bash
java -version      # must report 21.x
```

**Build the server.** The self-contained "fat jar" is the easiest to use — it carries
the `--doctor` self-test and the `--setup` client-wiring helper:

```bash
./gradlew :KSLServerMcp:shadowJar
# → KSLServerMcp/build/libs/ksl-mcp.jar
```

**Get a model to serve.** The server has no models built in — it discovers **bundle
JARs** from your workspace. Build the ready-made KSL Book Examples bundle:

```bash
./gradlew :KSLExamples:bookExamplesBundleJar
# → KSLExamples/build/libs/book-examples.jar   (16 textbook models)
```

Drop it into the server's bundle directory. That directory lives under your **KSL
workspace** — by default `~/Documents/KSLWork` (or `~/KSLWork` if you have no
`Documents` folder):

```bash
mkdir -p ~/Documents/KSLWork/KSLServer/bundles
cp KSLExamples/build/libs/book-examples.jar ~/Documents/KSLWork/KSLServer/bundles/
```

> **Where exactly?** The server watches `<KSLWork>/KSLServer/bundles/` and
> `<KSLWork>/bundles/`, and picks up a dropped jar within a few seconds — **no restart
> needed**. Not sure of your path? `--doctor` prints it (below). To pin a directory of
> your choice, set `KSL_BUNDLES_DIR`.

**Verify the server sees it** with the built-in self-test:

```bash
java -jar KSLServerMcp/build/libs/ksl-mcp.jar --doctor
```

```text
KSL MCP server - doctor
  version:     1.0.0
  bundle dirs: /home/you/Documents/KSLWork/KSLServer/bundles, /home/you/Documents/KSLWork/bundles
  bundles:     1
    - edu.uark.ksl.book-examples  models=[DriveThroughPharmacyWithQ, DriveThroughPharmacyWithResource,
      PalletWorkCenter, RQInventorySystem, StemFairMixerEnhanced, ... WalkInHealthClinic]
  OK - the server runs and 1 model bundle(s) are available.
```

If it says **`no model bundles were found`**, the jar is in the wrong place or isn't a
real bundle — see [§7](#7-troubleshooting--gotchas).

---

## 3. Connect your AI client

With stdio, **your client launches the server** as a subprocess — you just register it
in the client's MCP configuration.

**The easy way — let the server wire itself in:**

```bash
java -jar KSLServerMcp/build/libs/ksl-mcp.jar --setup
```

It detects installed agents (Claude Desktop, Cursor, Codex), merges in a `ksl` entry
(backing up the original once), and — if it finds none — prints the exact snippet to
paste yourself:

```text
KSL MCP server - setup

No supported agent detected (Claude Desktop / Codex).
Add this to your agent's MCP servers configuration:

"ksl": {
    "command": "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
    "args": [
        "-jar",
        "/home/you/.../KSLServerMcp/build/libs/ksl-mcp.jar",
        "--stdio"
    ]
}

Self-test any time:  java -jar ".../ksl-mcp.jar" --doctor
```

**The manual way.** Put that same `ksl` block under `mcpServers` in your client's config
(for Claude Desktop, `claude_desktop_config.json` — macOS
`~/Library/Application Support/Claude/`, Windows `%APPDATA%\Claude\`, Linux
`~/.config/Claude/`):

```json
{
  "mcpServers": {
    "ksl": {
      "command": "/absolute/path/to/java",
      "args": ["-jar", "/absolute/path/to/ksl-mcp.jar", "--stdio"]
    }
  }
}
```

> **Use absolute paths.** A GUI client doesn't inherit your shell `PATH`, so `command`
> must be the full path to `java` (the `--setup` output uses `java.home/bin/java` for
> exactly this reason), and the jar path must be absolute too.

**Restart the client fully.** The KSL tools then appear (in Claude Desktop, under
Settings → Connectors). Verify by asking:

> *"Use the ksl tools to list the available models."*

The assistant should call `list_bundles` and report the 16 book-examples models. You're
connected — go to [§4](#4-tutorial--your-first-session).

**Prefer HTTP?** Run the HTTP/SSE server instead (it stays up until you stop it):

```bash
./gradlew :KSLServerMcp:runHttp        # or the ksl-mcp-http launcher from installDist
```

```text
Application started in 0.248 seconds.
Responding at http://127.0.0.1:3001
```

Check it and point an HTTP MCP client at the base URL:

```bash
curl -s http://127.0.0.1:3001/health
# {"status":"UP","service":"ksl-mcp","version":"1.0.0"}
```

> **Exposing it on a network — read this first.** By default the HTTP server binds
> `127.0.0.1` (local only) with **no authentication**. To reach it from another
> machine, set `KSL_BIND_HOST=0.0.0.0` and require a bearer token with
> `KSL_AUTH_TOKEN=<secret>` — clients then send `Authorization: Bearer <secret>`
> (the `/health`, `/ready`, `/version` probes stay open). There is **no TLS**, so on
> an untrusted network put the server behind an SSH tunnel or a reverse proxy. Never
> expose it unauthenticated.

---

## 4. Tutorial — your first session

With the client connected and the book-examples bundle loaded, here is a real first
session. You type plain requests; the assistant calls tools and interprets the results.
The tool calls and outputs below are the actual protocol exchanges.

### Step 1 — Orient

Ask the assistant to get its bearings (or it will do this on its own on turn one). It
calls `get_started`, which returns the live model catalog and routes your goal to a
workflow:

```text
Available models now:
  - edu.uark.ksl.book-examples: DriveThroughPharmacyWithQ, DriveThroughPharmacyWithResource,
    PalletWorkCenter, RQInventorySystem, ... WalkInHealthClinic

Pick the path that matches the user's goal:
  - "Run one scenario and read the outputs"          → the run_a_model prompt
  - "Compare input settings / find which inputs matter" → the design_an_experiment prompt
  - "I have a dataset and want a probability distribution" → the fit_a_distribution prompt
  - "Find the inputs that minimize/maximize a response"   → the optimize_a_model prompt
```

### Step 2 — Discover the model

> *"Tell me about the DriveThroughPharmacyWithQ model."*

The assistant calls `describe_model`, which reports the model's outputs and its editable
inputs:

```text
Model DriveThroughPharmacyWithQ
  task kinds: SINGLE, SCENARIO, EXPERIMENT
  responses:  NumBusy, Num in System, System Time, PharmacyQ:NumInQ,
              PharmacyQ:TimeInQ, SysTime >= 4 minutes, Num Served
  inputs:     Pharmacy.numPharmacists  (Number of Pharmacists, min 1)
              ServiceTime.mean         (Mean Service Time, min)
```

### Step 3 — Run it

> *"Run it for 30 replications and summarize the results."*

The assistant calls `run_model` with `numberOfReplications: 30`. The model runs, and the
tool returns a result — a Markdown table plus a `resultId` you can refer back to:

```text
Result e8d0f843… — status: completed
Model DriveThroughPharmacyWithQ — replications 30/30 (COMPLETED_ALL_STEPS)

| Response             | Average  | Std Err | 95% CI half-width |
|----------------------|----------|---------|-------------------|
| NumBusy              |   0.4988 | 0.00081 |           0.00166 |
| Num in System        |   0.9932 | 0.00454 |           0.00928 |
| System Time          |   0.9944 | 0.00417 |           0.00852 |
| PharmacyQ:NumInQ     |   0.4945 | 0.00403 |           0.00825 |
| PharmacyQ:TimeInQ    |   0.4950 | 0.00386 |           0.00790 |
| SysTime >= 4 minutes |   0.0176 | 0.00069 |           0.00142 |
| Num Served           | 14981.73 |   20.06 |             41.03 |

Next steps: compare settings with a scenario batch (run_config) · optimize inputs
(run_optimization) · drill into one response (get_response).
```

### Reading the results

A good assistant won't just dump the table — it will read it with you. The key ideas:

- **System Time** (minutes a customer spends in the system) averages **0.9944** across
  the 30 replications, with a 95% CI half-width of **0.0085** — so the true long-run mean
  is plausibly in **[0.986, 1.003]** minutes. The half-width is the *margin of error*; a
  smaller one means a more precise estimate, which you buy with more replications or a
  longer run.
- **NumBusy ≈ 0.4988** means the single pharmacist is busy about **50%** of the time.
- **Num Served ≈ 14,982** customers over the run.
- Each result carries a **`resultId`** (`e8d0f843…`). Later in the session you can ask the
  assistant to `get_result` / `get_response` / `get_artifacts` for it — no re-run needed
  — and `list_results` finds work from earlier sessions. Ask `get_workspace` to see where
  the server wrote the run's files.

### Try another workflow

From here you can ask the assistant to go further with the same model catalog, and it
will route to the matching tools — for example *"which is better, 2 or 3 pharmacists?"*
(a designed experiment), *"find the cheapest staffing that keeps System Time under a
minute"* (optimization), or *"here's a column of service times — what distribution fits?"*
(distribution fitting).

---

## 5. Reference

### The full tool catalog

**Orientation** — `get_started`.
**Discovery** — `list_bundles`, `list_models`, `describe_model`.
**Run (single)** — `run_model`, `run_config`, `submit_run`, `get_run_events`,
`get_run_result`, `cancel_run`, `run_template`, `validate_run_config`, `preview_run_config`.
**Experiments** — `run_experiment`, `experiment_config`, `experiment_template`,
`validate_experiment_config`, `preview_experiment_config`, `get_design_point`,
`experiment_regression`.
**Optimization** — `run_optimization`, `run_optimization_config`, `optimization_template`,
`validate_optimization_config`, `preview_optimization_config`.
**Distribution fitting** — `fit_dataset`, `fit_config`, `fit_template`, `validate_fit_config`,
`preview_fit_config`, `get_fit_scoring`, `get_fit_report`, `get_fit_data_summary`.
**Data & statistics** — `summarize_data`, `acf_analysis`, `shift_analysis`,
`family_frequency_bootstrap`.
**Random variates** — `list_distributions`, `generate_variates`.
**Results & artifacts** — `get_result`, `list_responses`, `get_response`, `get_artifacts`,
`get_artifact`, `list_results`.
**Database analysis** — `db_open_external`, `db_status`, `db_experiments`, `db_summary`,
`db_compare`, `db_views`, `db_view`, `db_compare_report`, `db_export`, `db_summary_report`.
**Animation & layout** — `auto_layout`, `validate_animation_layout`, `render_animation_layout`,
`export_layout`.
**Bundle authoring** — `bundle_authoring_candidates`, `preview_bundle_authoring`, `assemble_bundle`.
**Documents** — `save_document`, `load_document`, `list_documents`, `delete_document`.
**Workspace** — `get_workspace`, `set_workspace`.

**Guided prompts** (surfaced by the client as ready-made starting points):
`run_a_model`, `optimize_a_model`, `fit_a_distribution`, `generate_random_variates`,
`explore_a_model`, `design_an_experiment`, `get_started`.

Every tool comes in a family: a quick one-shot form (`run_model`), a full-document form
(`run_config`), a `*_template` to scaffold that document, a `validate_*` to check it, and
a `preview_*` to see its cost *before* running. Long runs use `submit_run` →
`get_run_events` → `get_run_result`.

### Launchers, endpoints & modes

| Launcher / mode | Transport | Purpose |
|---|---|---|
| `KSLServerMcp` (installDist) or `ksl-mcp.jar --stdio` | stdio | the server an AI client runs |
| `ksl-mcp-http` (installDist) or `:KSLServerMcp:runHttp` | HTTP/SSE | a standing server on port 3001 |
| `ksl-mcp.jar --doctor` | — | self-test: version, bundle dirs, bundles found |
| `ksl-mcp.jar --setup` / `--remove` | — | wire / unwire the server into detected clients |
| `ksl-mcp.jar --version` | — | print the server version |

HTTP health routes: `GET /health`, `GET /ready` (503 until the first bundle scan finishes),
`GET /version`.

### Key configuration

Read from environment variables (highest priority), then `~/.ksl/config.toml`, then
defaults. The most-used knobs:

| Env var | Default | What it does |
|---|---|---|
| `KSL_BUNDLES_DIR` | `<KSLWork>/KSLServer/bundles` + `<KSLWork>/bundles` | where the server looks for model bundles |
| `KSL_MCP_PORT` | `3001` | HTTP server port |
| `KSL_BIND_HOST` | `127.0.0.1` | HTTP bind address (`0.0.0.0` to expose on a LAN) |
| `KSL_AUTH_TOKEN` | *(none)* | when set, HTTP requires `Authorization: Bearer <token>` |
| `KSL_RUN_TIMEOUT_SECONDS` | `0` (no limit) | cap on a single run |

Every knob has a `KSL_*` environment override (shown) and a `~/.ksl/config.toml`
equivalent; other settings there include the result cache and `maxConcurrentJobs`
(default: one per CPU core).

---

## 6. Common tasks

| Task | How |
|---|---|
| Serve **your own** model | Compile it to a *builders JAR* (public no-arg `ModelBuilderIfc` classes), then `java -jar kslpkg.jar assemble your-builders.jar --id your.bundle.id` and drop the resulting `*-bundle.jar` into `<KSLWork>/KSLServer/bundles/`. See [Bundle Tools](bundle-tools.md). |
| Pin the bundle directory | `export KSL_BUNDLES_DIR=/path/to/bundles` before launching |
| Run a standing HTTP server | `./gradlew :KSLServerMcp:runHttp` (Ctrl-C to stop) |
| Re-wire / remove a client | `ksl-mcp.jar --setup` / `ksl-mcp.jar --remove` |
| Find earlier results | ask the assistant to call `list_results`, then `get_result <id>` |
| Save a config to reuse | ask it to `save_document` (and `load_document` next session) |
| Check the server is healthy | `ksl-mcp.jar --doctor` (stdio) or `curl .../health` (HTTP) |

To reach the server from another machine, see the security note in
[§3](#3-connect-your-ai-client).

---

## 7. Troubleshooting & gotchas

| Symptom | Cause | Fix |
|---|---|---|
| KSL tools don't appear after `--setup` | The client wasn't fully restarted, or the config path is wrong. | Quit and reopen the client; confirm the `ksl` entry is in the right `mcpServers` config file. |
| `list_bundles` is empty / *"not a KSL bundle (no META-INF/ksl/bundle.toml manifest)"* | The jar is a plain/ServiceLoader jar, not an **assembled** bundle. | Run `kslpkg assemble` on it first (see [§6](#6-common-tasks) / [Bundle Tools](bundle-tools.md)). |
| Bundle jar present but not found | It's in the wrong directory (the default is under **`KSLWork`**, not `~/.ksl`), or it bundled KSLCore. | Put it in `<KSLWork>/KSLServer/bundles/` (check `--doctor`'s reported path); a bundle must **not** package KSLCore. |
| The client shows garbled output / protocol errors (stdio) | A bundled model printed to **stdout**, which is the MCP channel. | Never `println` in a bundled model; use logging (stderr) instead. |
| `UnsupportedClassVersion` on launch | Wrong Java. | Use JDK 21 (`java -version`). |
| HTTP: port in use, `401`, or `/ready` returns `503` | Port conflict, missing/invalid bearer token, or the first bundle scan hasn't finished. | Change `KSL_MCP_PORT`; check the `Authorization: Bearer` token; or just retry — `/ready` is `503` only until the first bundle scan completes. |
| *"server is at capacity"* | Too many concurrent jobs. | Raise `server.maxConcurrentJobs`, or retry. |
| A run never finishes | An unbounded model. | Set `KSL_RUN_TIMEOUT_SECONDS`. |

---

## 8. See also

- The **REST server** — a sibling transport that drives the same models over plain
  HTTP for scripts and web apps (user guide planned).
- [Bundle Tools](bundle-tools.md) — package your own models into loadable bundles (`kslpkg`).
- The desktop app guides — [Single](single.md), [Scenario](scenario.md),
  [Experiment](experiment.md), [Simopt](simopt.md), [Results](results.md),
  [Distribution](distribution.md) — the GUI way to reach the same KSL capabilities.
- [KSL Book](https://rossetti.github.io/KSLBook/) — the simulation concepts behind the
  pharmacy model and the statistics you read above.

---

<sub>The command and tool transcripts on this page are real output captured from
`ksl-mcp.jar` (version 1.0.0) driving the KSL Book Examples bundle, lightly abridged for
length (long decimals rounded; environment banners removed).</sub>
