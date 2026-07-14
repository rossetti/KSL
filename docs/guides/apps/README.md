# KSL Application Guides

Step-by-step, user-facing guides for the KSL applications. Most are **desktop
apps** — each guide walks through the app click-by-click with a concrete worked
example. **Bundle Tools** (`kslpkg`) and the **MCP Server** are command-line and
server tools, driven from a terminal.

**First time?** **[Install the KSL suite](install.md)** — one command installs all
these apps, the servers, and `kslpkg` into a single `KSLWork` folder, running on your
own Java 21 (no build required).

New to these apps? Read **[Common UI & concepts](common-ui.md)** next — it covers
the parts every app shares (models & bundles, the workspace, themes, the run
console, reports), so the individual guides don't repeat them.

## The apps

| Guide | What it's for | Status |
|---|---|---|
| **[Single-Model](single.md)** | Run one model, set its inputs, read a report. The best starting point. | ✅ Available |
| **[Scenario](scenario.md)** | Compare several configurations of a model side by side. | ✅ Available |
| **[Experiment](experiment.md)** | Vary inputs over a designed (factorial) experiment. | ✅ Available |
| **[Simopt](simopt.md)** | Search for the input settings that optimize a response. | ✅ Available |
| **[Animation](animation.md)** | Watch a model run as a visual, replayable animation — capture, run, lay out, replay. | ✅ Available |
| **[Results](results.md)** | Browse and compare results saved in a simulation database. | ✅ Available |
| **[Distribution](distribution.md)** | Fit probability distributions to data. | ✅ Available |
| **[Bundle Workbench](bundle-workbench.md)** | Package models as bundle JARs in a guided desktop app — open, identify, catalog, validate, assemble. | ✅ Available |
| **[Bundle Tools](bundle-tools.md)** | Package models as loadable bundle JARs (`kslpkg`, command line). | ✅ Available |

## Servers

Prefer to drive KSL from outside a GUI? The server modules expose KSL's
capabilities to programs and AI assistants. Three separate **MCP servers** give an
AI assistant searchable, tool-driven access — to running models, to the source code,
and to the textbook — each running as its own process.

| Guide | What it's for | Status |
|---|---|---|
| **[MCP Server](mcp-server.md)** | Run and analyze models from an AI assistant (Claude Desktop, Cursor, Codex) over the Model Context Protocol. | ✅ Available |
| **[Code MCP Server](mcp-server-code.md)** | Give an AI assistant searchable access to the KSL **source code** and API. | ✅ Available |
| **[Book MCP Server](mcp-server-book.md)** | Give an AI assistant searchable access to the KSL **textbook**. | ✅ Available |
| **REST Server** | Drive models over plain HTTP from scripts and web apps. The server module ships; a user guide is planned. | Guide planned |

## How the apps relate

```mermaid
flowchart TD
    bundle["Model bundle JAR<br/>(Bundle Workbench / kslpkg)"]
    bundle --> single["Single-Model<br/>run one model"]
    bundle --> scenario["Scenario<br/>compare configurations"]
    bundle --> experiment["Experiment<br/>designed experiment"]
    bundle --> simopt["Simopt<br/>optimize inputs"]
    single --> db[("Results database<br/>+ reports")]
    scenario --> db
    experiment --> db
    simopt --> db
    db --> results["Results<br/>browse & compare"]
    data["Your data"] --> distribution["Distribution<br/>fit a distribution"]
    distribution -.->|"input models for"| bundle
```

A model is packaged once as a **bundle**, then run by the **Single**, **Scenario**,
**Experiment**, or **Simopt** apps. Those runs write a **results database and
reports**, which the **Results** app browses and compares. The **Distribution** app
is the front of the pipeline — it fits distributions to data that feed your models.

## For guide authors

The standard structure for every guide is in **[`_TEMPLATE.md`](_TEMPLATE.md)**.
