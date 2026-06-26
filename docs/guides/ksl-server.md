# Guide: Running KSL as a Server (MCP & REST)

A task-oriented guide for **operators and integrators**: how to run KSL's
simulation capabilities — run, experiment, optimize, distribution-fitting — as a
*server*, driven by an AI assistant over MCP or by scripts and web apps over
REST, without compiling your models into anything. Three transports, one set of
capabilities. The only prerequisite is **Java 21**; you do **not** need Docker.

This guide ties together pieces documented elsewhere rather than replacing them:

- The **[`ksl-model-bundling`](ksl-model-bundling.md)** guide is the deep
  reference for *authoring* the model bundles a server serves (controls, catalog,
  the `KSLModelBundle` SPI). This guide only shows the drop-in.
- The **[Bundle Tools (`kslpkg`)](apps/bundle-tools.md)** guide covers inspecting
  and enriching bundle jars from the command line.

---

## 1. What this guide is for

You have working KSL models and want to drive them from *outside* the JVM —
an AI coding agent, a script, a web app, another machine — instead of writing a
`main()` for each run. The server stack exposes KSL's capabilities over three
transports, all backed by the same headless service core:

| Server | Transport | How it runs | Use it for |
|---|---|---|---|
| **MCP-stdio** | stdin/stdout | an **AI client launches it on demand** as a child process | using KSL from an AI assistant (Claude Desktop, an IDE, etc.) |
| **MCP-HTTP** | HTTP (SSE) | you **start it once**, clients connect over the network | a long-running MCP endpoint reached from another of your machines |
| **REST** | HTTP (JSON) | you **start it once**, clients hit URLs | scripts, web apps, quick `curl` testing |

```bash
java -version   # must report 21.x
```

> **New in this release.** The server stack (`KSLServiceCore`, `KSLServerMcp`,
> `KSLServerRest`) is a recent addition. Its model-delivery mechanism — bundle
> jars in `~/.ksl/bundles` — is the same one the desktop apps use.

---

## 2. The mental model

**One capability set, three transports.** A transport-agnostic *service core*
holds the simulation capabilities; each transport (Ktor for HTTP, the MCP SDK for
MCP) wraps it without leaking its dependencies into the core. All three servers
share the same configuration (`~/.ksl/config.toml` + environment), result cache,
and bundle directory, and report the same build version.

**Bundles are how your models reach a server.** A *bundle* is a small jar
containing a class that implements `KSLModelBundle` and lists the models it
provides. A running server **auto-loads** any bundle jar dropped into
`KSL_BUNDLES_DIR` (`~/.ksl/bundles` by default), rescanning every ~5 s — no
restart. If two jars declare the same `bundleId`, the catalog keeps **one** entry
and the **newest build wins**.

**Two lifecycles.** With **MCP-stdio** the AI client starts and stops the server
for you — there is no port and nothing to run by hand. With the **HTTP** servers
(REST, MCP-HTTP) *you* start a long-running process and stop it with Ctrl-C.

**The spine.** However you connect, the workflow is the same: discover bundles →
list models → describe a model → run it → retrieve the result.

---

## 3. Quick start — a first run end to end

The happy path with the REST server and the shipped sample bundle. (No
command-line experience? Use the fully step-by-step recipe in
[§4](#a-first-run-step-by-step-no-command-line-experience-needed) instead.)

```bash
# 1. Build a REST distribution and the sample model bundle (needs JDK 21):
./gradlew :KSLServerRest:installDist :KSLExamples:sampleBundleJar
#   -> KSLServerRest/build/install/KSLServerRest/bin/KSLServerRest
#   -> KSLExamples/build/libs/mm1-sample.jar

# 2. Drop the model where the server looks:
mkdir -p ~/.ksl/bundles
cp KSLExamples/build/libs/mm1-sample.jar ~/.ksl/bundles/

# 3. Start the server (Ctrl-C to stop):
./KSLServerRest/build/install/KSLServerRest/bin/KSLServerRest

# 4. In another terminal — it's up and sees the model:
curl -sS http://127.0.0.1:8080/health     # {"status":"UP","service":"ksl-rest","version":"1.0.0"}
curl -sS http://127.0.0.1:8080/bundles     # [{"bundleId":"ksl.examples.mm1",...,"modelIds":["MM1"]}]

# 5. Run the MM1 model for 5 replications:
curl -sS -X POST http://127.0.0.1:8080/runs -H "Content-Type: application/json" \
  -d '{"bundleId":"ksl.examples.mm1","modelId":"MM1","numberOfReplications":5}'
#   {"jobId":"...","status":"RUNNING","resultId":"...",...}

# 6. Fetch the result (use the jobId from step 5):
curl -sS http://127.0.0.1:8080/runs/<jobId>/result
#   {"type":"completed","summary":{...,"completedReplications":5,...},"responses":[...]}
```

That's the whole loop: **write a model → package it as a bundle jar → drop it in
→ the running server serves and runs it.**

---

## 4. How do I…?

### Get a distribution

A "distribution" is a self-contained folder — launcher scripts in `bin/`, all
jars in `lib/` — that runs on any machine with Java 21, no Gradle or source tree.

```bash
# Unpacked in build/install/... (handy while developing):
./gradlew :KSLServerMcp:installDist :KSLServerRest:installDist

# Or a release archive to copy elsewhere and unzip:
./gradlew :KSLServerMcp:distZip :KSLServerRest:distZip
#   -> KSLServerMcp/build/distributions/KSLServerMcp-<version>.zip
#   -> KSLServerRest/build/distributions/KSLServerRest-<version>.zip
```

The launcher scripts resolve their own location, so you can run them from any
working directory or move the folder anywhere. (On Windows, use the matching
`.bat` files.) See [§5](#5-key-commands-endpoints--config) for the launcher list.

### Use it from an AI client (MCP-stdio)

The AI client starts the server itself — you run nothing by hand and there is
**no port**. Point the client's `mcpServers` config at the **absolute path** of
the `KSLServerMcp` launcher:

```json
{
  "mcpServers": {
    "ksl": {
      "command": "/absolute/path/to/KSLServerMcp-1.0.0/bin/KSLServerMcp",
      "args": [],
      "env": { "KSL_BUNDLES_DIR": "/home/you/.ksl/bundles" }
    }
  }
}
```

- `command` **must be an absolute path** — AI clients don't run it from your
  shell. On Windows use the `.bat`.
- `env` is optional; every setting has a sensible default ([§5](#5-key-commands-endpoints--config)).
- The server speaks MCP on **stdout** and logs to **stderr**, so the channel
  stays clean. Restart the client after editing its config; it should then list
  the KSL tools (discovery, run, experiment, optimization, …).

**The single-jar student path.** `KSLServerMcp` also builds a self-contained
`ksl-mcp.jar` (`./gradlew :KSLServerMcp:shadowJar`) that wires up an agent for
you: **double-click it** to open a small setup window and click "Configure my
coding agent," or run `java -jar ksl-mcp.jar --setup`. Verify with
`java -jar ksl-mcp.jar --doctor`; undo with `--remove`.

### Run an HTTP server (local or LAN)

Start a server once; it stays up until you stop it (Ctrl-C). By default the HTTP
servers bind to `127.0.0.1`, reachable only from this machine:

```bash
KSL_REST_PORT=8080 ./KSLServerRest-1.0.0/bin/KSLServerRest      # REST  on 127.0.0.1:8080
KSL_MCP_PORT=3001  ./KSLServerMcp-1.0.0/bin/ksl-mcp-http        # MCP   on 127.0.0.1:3001

curl http://127.0.0.1:8080/health    # {"status":"UP",...}
curl http://127.0.0.1:8080/ready      # 200 once the initial bundle scan finishes
```

To let another computer on your network reach it, bind to all interfaces — then
**secure it** (next recipe):

```bash
KSL_BIND_HOST=0.0.0.0 KSL_REST_PORT=8080 ./KSLServerRest-1.0.0/bin/KSLServerRest
# from the other machine, use this host's LAN address:
curl http://192.168.1.50:8080/health
```

> **Security note.** By default these servers have **no authentication** (the
> local-trust model). Binding to `0.0.0.0` exposes the server to everything on
> that network. Never expose an unauthenticated server on public/shared Wi-Fi.

### Secure cross-machine access

The defaults assume a single trusted machine. When you expose an HTTP server,
add one of these (neither needs HTTPS/TLS):

**Option A — Bearer token (built in).** Set a shared secret; the HTTP servers
then require it on every request **except** the `/health`, `/ready`, `/version`
probes (so monitoring needs no secret). The stdio server has no network surface
and is unaffected.

```bash
KSL_AUTH_TOKEN='a-long-random-shared-secret' KSL_BIND_HOST=0.0.0.0 \
  KSL_REST_PORT=8080 ./KSLServerRest-1.0.0/bin/KSLServerRest

curl -H "Authorization: Bearer a-long-random-shared-secret" http://192.168.1.50:8080/bundles
```

Use a long, random token (the comparison is constant-time), prefer the env var
over `config.toml` so it isn't sitting in a file, and add the same
`Authorization` header to an MCP-HTTP client's transport config. The token
authenticates "you hold the key," not which user — fine for small lab testing.

**Option B — SSH tunnel (no server change).** Keep the server on **localhost**
(the default) and reach it over SSH; you get encryption and key-based auth for
free, with nothing open on the LAN:

```bash
# on the SERVER (default localhost bind):
KSL_REST_PORT=8080 ./KSLServerRest-1.0.0/bin/KSLServerRest
# on the CLIENT — forward a local port through SSH:
ssh -L 8080:localhost:8080 you@192.168.1.50
curl http://localhost:8080/health        # tunnels securely to the server
```

A firewall allowlist (e.g. `ufw allow from <client-ip> to any port 8080`) is a
third, OS-level option — access control, not authentication; combine it with A or B.

### Add (or update) your own models

Your model reaches a server as a **bundle jar** that contains **only your classes
plus a one-line registration file** — it does **not** bundle KSLCore, which the
server already provides. The shipped `sampleBundleJar` Gradle task is the template
(see `KSLExamples/build.gradle.kts`); the full authoring reference is the
[`ksl-model-bundling`](ksl-model-bundling.md) guide.

```
META-INF/services/ksl.app.bundle.KSLModelBundle
    -> ksl.examples.general.appsupport.MM1Bundle   (one fully-qualified class per line)
```

**Deploy dynamically (recommended)** — drop the jar in the watched directory; a
running server picks it up within its poll interval, no restart:

```bash
cp my-model.jar ~/.ksl/bundles/        # appears within ~5 s
curl -s http://127.0.0.1:8080/bundles  # confirm it loaded
```

Dropping an **updated** copy is safe: if two jars declare the same `bundleId`, the
catalog keeps one entry and the **newest build wins** (by the jar's `Build-Time`,
else file mtime; identical copies collapse). The active entry discloses what it
superseded, and removing it **promotes** the runner-up. So you can hand out
`mm1-v2.jar` next to `mm1-v1.jar` with no manual cleanup. (To bake a fixed model
set in instead, put the jar on the server's classpath in the dist's `lib/`.)

### A first run, step by step (no command-line experience needed)

Do each step in order. After each there's a **✅ What you should see**. You need
only **Java 21** — no source code or developer tools. Get two files from your
instructor first: a server zip (`KSLServerRest-1.0.0.zip` and/or
`KSLServerMcp-1.0.0.zip`) and a model file (`mm1-sample.jar`); put them in your
**Downloads** folder.

> **Instructor — make those files once in the repo:**
> `./gradlew :KSLServerRest:distZip :KSLServerMcp:distZip :KSLExamples:sampleBundleJar`

**Step 0 — Open a terminal and check Java.** macOS: `Cmd`+`Space`, type
`Terminal`. Linux: your Terminal app. Windows: Start → `PowerShell` (use
PowerShell, not the old Command Prompt). Then:

```
java -version
```

✅ a line containing `version "21` (e.g. `openjdk version "21.0.2"`). If it's
missing or lower than 21, install Temurin 21 from <https://adoptium.net>, reopen
the terminal, and try again.

#### Part 1 — Run a simulation with the REST server

**Step 1 — Unzip the server.** Double-click `KSLServerRest-1.0.0.zip` (Windows:
then "Extract All"), or on Linux `cd ~/Downloads && unzip KSLServerRest-1.0.0.zip`.
✅ a folder `KSLServerRest-1.0.0` containing `bin` and `lib`.

**Step 2 — Put the model where the server looks.**

```bash
# macOS / Linux:
mkdir -p ~/.ksl/bundles && cp ~/Downloads/mm1-sample.jar ~/.ksl/bundles/
```
```powershell
# Windows (PowerShell):
New-Item -ItemType Directory -Force "$HOME\.ksl\bundles"
Copy-Item "$HOME\Downloads\mm1-sample.jar" "$HOME\.ksl\bundles\"
```

✅ no error.

**Step 3 — Start the server** from inside the unzipped folder. macOS/Linux:
`cd ~/Downloads/KSLServerRest-1.0.0 && ./bin/KSLServerRest`. Windows:
`cd "$HOME\Downloads\KSLServerRest-1.0.0"` then `.\bin\KSLServerRest.bat`.
✅ the command does **not** return to the prompt — it "hangs," which is correct.
**Leave this window open** (Ctrl-C later stops the server).

**Step 4 — In a SECOND terminal, check it's alive and sees the model.** (Windows:
type `curl.exe`, not plain `curl`.)

```
curl -sS http://localhost:8080/health
curl -sS http://localhost:8080/bundles
```

✅ `{"status":"UP","service":"ksl-rest","version":"1.0.0"}`, then text mentioning
`"bundleId":"ksl.examples.mm1"` and `"modelIds":["MM1"]` — your model, loaded from
the file you copied in Step 2.

**Step 5 — Run the simulation.** Put the request in a file to avoid tricky
quoting. Create `run.json` (a plain-text editor's *Save As* → "All Files", name it
exactly `run.json`) containing:

```json
{"bundleId":"ksl.examples.mm1","modelId":"MM1","numberOfReplications":5}
```

Send it from the second terminal (Windows: `curl.exe`):

```
curl -sS -X POST http://localhost:8080/runs -H "Content-Type: application/json" -d @run.json
```

✅ something like `{"jobId":"059329fd-…","status":"RUNNING",...}`. **Copy the
`jobId` value.**

**Step 6 — Read the results.** Replace `PASTE-JOB-ID` with the id you copied:

```
curl -sS http://localhost:8080/runs/PASTE-JOB-ID/result
```

✅ a block starting `{"type":"completed"` with `"completedReplications":5` and a
`"responses"` list of statistics. **That's a finished simulation.** (If you see
`RUNNING` or "not ready," wait a second and rerun.)

**Step 7 — Stop the server.** Click the first window and press `Ctrl`+`C`. 🎉

#### Part 2 — Use the model from an AI client (MCP)

Here the **AI client starts the server for you** — you just tell it where the
launcher is. Uses the `KSLServerMcp` zip and the same `mm1-sample.jar`. The
example client is **Claude Desktop**.

**Step 1 — Unzip `KSLServerMcp-1.0.0.zip`** and note the **full path** to its
launcher: `bin/KSLServerMcp` (macOS/Linux) or `bin\KSLServerMcp.bat` (Windows).
Tip: `cd` into the folder and run `pwd` (macOS/Linux) or `Get-Location` (Windows),
then append the launcher path.

**Step 2 — Make sure the model is available.** If you did Part 1 it already is;
otherwise redo Part 1 Step 2.

**Step 3 — Tell the client about the server.** Edit Claude Desktop's config
(macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`;
Windows: `%APPDATA%\Claude\claude_desktop_config.json`; or **Settings → Developer
→ Edit Config**), using **your** full path from Step 1:

```json
{ "mcpServers": { "ksl": { "command": "/Users/you/Downloads/KSLServerMcp-1.0.0/bin/KSLServerMcp" } } }
```

On Windows the value needs **double backslashes**, e.g.
`"C:\\Users\\you\\Downloads\\KSLServerMcp-1.0.0\\bin\\KSLServerMcp.bat"`. If the
file already had a `"mcpServers"` block, add the `"ksl"` entry inside it.

**Step 4 — Restart and try it.** Fully **quit and reopen** the client.
✅ it lists KSL tools (in recent Claude Desktop, under **Settings → Connectors**:
`list_bundles`, `list_models`, `run_model`, …). Then ask, in plain language:

> "Use the ksl tools to list the available models, then run the MM1 model for 10
> replications and summarize the results."

❌ If nothing appears: fully quit and reopen (not just the window); enable
developer/MCP settings; confirm the `command` is the **full** path (Windows: ends
in `.bat` with `\\`); and check `java` is on the PATH the GUI app sees.

### Operate a running server (day-2)

**Start / stop.** Foreground is simplest (Ctrl-C stops). To run in the background
and capture logs:

```bash
KSL_REST_PORT=8080 ./bin/KSLServerRest > ~/.ksl/rest.log 2>&1 &
echo $! > ~/.ksl/rest.pid
kill "$(cat ~/.ksl/rest.pid)"          # stop later
jps -l | grep ksl.server               # what's running
```

The HTTP servers stop cleanly on SIGTERM/SIGINT (a shutdown hook closes the
bundle watcher, jobs, and registry). MCP-stdio is started/stopped by the AI
client.

**Logs.** There is **no log file by default** — each server logs to its console
(REST → stdout, MCP → stderr), both at `WARN`; capture by redirecting the process
output as above. MCP uses stderr deliberately: on stdio, stdout *is* the protocol
channel. Raise verbosity by pointing Logback at your own config:

```bash
JAVA_OPTS="-Dlogback.configurationFile=/path/to/my-logback.xml" ./bin/KSLServerRest
```

**Result cache.** Completed results are cached so identical requests return
immediately — an in-memory tier (cleared on restart) and a JSON-on-disk tier
under `~/.ksl/result-cache/` (`KSL_RESULT_CACHE_DIR`). Results are recomputable,
so clearing the cache loses nothing; cleanest with the server stopped:

```bash
du -sh ~/.ksl/result-cache/      # inspect
rm -rf ~/.ksl/result-cache/*     # clear
```

**State & upgrades.** All persistent state lives under `~/.ksl/` (`config.toml`,
`bundles/`, `result-cache/`). To upgrade, replace the distribution folder and
restart — your config, bundles, and cache are untouched; confirm with
`GET /version`.

---

## 5. Key commands, endpoints & config

**Launchers** (in a distribution's `bin/`; Windows `.bat`):

| Launcher | Starts |
|---|---|
| `KSLServerRest` | REST server (HTTP/JSON, port 8080) |
| `ksl-mcp-http` | MCP-HTTP server (HTTP/SSE, port 3001) |
| `KSLServerMcp` | MCP-stdio server (launched by the AI client) |

**HTTP endpoints** (REST shown; MCP-HTTP shares the probes):

| Endpoint | Meaning |
|---|---|
| `GET /health` | process is up — `200 {"status":"UP",...}` |
| `GET /ready` | initial bundle scan finished — `200` ready / `503` still starting |
| `GET /version` | build version (matches across all servers) |
| `GET /bundles` | the loaded bundle catalog (ids, display names, model ids) |
| `POST /runs` | submit a run; returns a `jobId` |
| `GET /runs/{jobId}/result` | fetch a submitted run's result |

The three probes stay unauthenticated even when the token gate is on.

**Configuration.** Settings come from `~/.ksl/config.toml` (all keys optional),
each overridable by an environment variable; config and env are read at
**startup**, so restart to apply (bundles are the exception — they hot-load):

| Env var | Overrides | Default |
|---|---|---|
| `KSL_CONFIG_FILE` | path to the config file | `~/.ksl/config.toml` |
| `KSL_BUNDLES_DIR` | where bundles are discovered/watched | `~/.ksl/bundles` |
| `KSL_RESULT_CACHE_DIR` | where run results are cached | `~/.ksl/result-cache` |
| `KSL_BIND_HOST` | HTTP bind interface | `127.0.0.1` (localhost only) |
| `KSL_REST_PORT` | REST listen port | `8080` |
| `KSL_MCP_PORT` | MCP-HTTP listen port | `3001` |
| `KSL_RUN_TIMEOUT_SECONDS` | per-job wall-clock deadline (`0` = no limit) | `0` |
| `KSL_AUTH_TOKEN` | shared bearer token for the HTTP servers ([§4](#secure-cross-machine-access)) | none (no auth) |

**The `ksl-mcp.jar` modes** (the self-contained single jar from `:KSLServerMcp:shadowJar`):

| Command | What it does |
|---|---|
| double-click / `java -jar ksl-mcp.jar` | open the setup window (console fallback if headless) |
| `--setup` | console: detect agents and wire them up |
| `--gui` | force the setup window |
| `--doctor` | self-test: version + the model bundles found |
| `--remove` | undo setup (remove the `ksl` entry) |
| `--stdio` | run the MCP server (what the agent invokes — not by hand) |
| `--version` | print the version |

---

## 6. Gotchas & troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Address already in use` on start | the port is taken | change `KSL_REST_PORT`/`KSL_MCP_PORT`, or stop the other process (`jps -l`, then `kill`) |
| `UnsupportedClassVersion` / won't start | wrong Java | install/select **Java 21** (`java -version`) |
| Double-click does nothing | `.jar` not associated with Java | run `java -jar ksl-mcp.jar` in a terminal |
| Can't reach the server from another machine | bound to localhost | start with `KSL_BIND_HOST=0.0.0.0` — and secure it ([§4](#secure-cross-machine-access)) |
| `401 {"error":"unauthorized"}` | the token gate is on | send `Authorization: Bearer <token>`; probes never need it |
| `/ready` returns `503` | still doing the initial bundle scan | wait a moment; it flips to `200` |
| Dropped bundle jar doesn't appear in `/bundles` | wrong dir, missing `META-INF/services/...KSLModelBundle` entry, or the jar bundled KSLCore | confirm `KSL_BUNDLES_DIR`; check the services file lists the bundle class; rebuild **without** KSLCore ([§4](#add-or-update-your-own-models)) |
| `503 "at capacity (N)"` on submit | `maxConcurrentJobs` reached | wait and retry, or raise `server.maxConcurrentJobs` |
| A run/experiment/optimization never finishes | a runaway model | set `KSL_RUN_TIMEOUT_SECONDS`; the job is cancelled at the deadline and reported `Cancelled` |
| MCP stdio client sees garbled / protocol errors | something wrote to stdout | ensure nothing — including model code — prints to stdout; server logs already go to stderr |
| AI client shows no tools after setup | not fully restarted, or wrong UI spot | **fully quit** the client; in Claude Desktop look under **Settings → Connectors**; ensure developer/MCP settings are on |

> **Never `println` to stdout from model code** running under the stdio server —
> stdout is the MCP protocol channel and a stray line corrupts it.

---

## 7. See also

- [`ksl-model-bundling`](ksl-model-bundling.md) — authoring a bundle-ready model:
  controls, the catalog, the `KSLModelBundle` SPI, Gradle wiring.
- [Bundle Tools (`kslpkg`)](apps/bundle-tools.md) — inspect/enrich bundle jars.
- The desktop-app guides ([Single](apps/single.md), [Scenario](apps/scenario.md),
  [Experiment](apps/experiment.md), [Simopt](apps/simopt.md)) — the other
  consumers of the same bundles.
