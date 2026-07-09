# KSL usage guides

Task-oriented guides covering the major KSL packages. Most follow the
same 7-section template (overview, mental model, quick start, recipes,
key types, gotchas, see also); a few — where a package's scope genuinely
doesn't fit seven sections — extend it, and say so near the top.
Library-guide code snippets are compile-verified against the source on
every build.

> **Looking for the desktop apps?** Step-by-step, student-facing user
> guides for the KSL desktop applications (Single, Scenario, Experiment,
> Simopt, Results, Distribution) and the `kslpkg` CLI live under
> [`apps/`](apps/README.md). Those are GUI walkthroughs with real
> screenshots; the guides below are developer/library references.

## Reading order

If you're new to KSL, read **Foundation** in order, then pick the
**modeling-domain package** that matches your problem, then dip into
the utility guides as you need them.

---

## Foundation

How the framework works.

| Guide | What it covers |
|---|---|
| [`ksl-simulation`](ksl-simulation.md) | `Model`, `ModelElement`, replications, the executive |
| [`ksl-modeling`](ksl-modeling.md) | `variable`, `queue`, elements, NHPP — the core modeling primitives |
| [`ksl-observers`](ksl-observers.md) | `ModelElementObserver`, observer wiring patterns |
| [`ksl-controls`](ksl-controls.md) | The controls package |
| [`ksl-controls-experiments`](ksl-controls-experiments.md) | `ksl.controls.experiments` — designed experiments and scenarios, sequential or concurrent |

## Modeling-domain packages

The high-level modeling abstractions. Pick the one whose mental
model matches your problem.

| Guide | When to use |
|---|---|
| [`ksl-entity`](ksl-entity.md) | **Process view** — each entity's life is written as a suspending coroutine (`delay`, `seize`, `release`, `move`) |
| [`ksl-spatial`](ksl-spatial.md) | **Spatial substrate** — locations, distances, movable resources; the substrate that `move` operates on |
| [`ksl-station`](ksl-station.md) *(experimental)* | **Queueing-network view** — passive stations route jobs |
| [`ksl-agent`](ksl-agent.md) *(experimental)* | **Agent-based view** — statechart-reactive autonomous actors |
| [`ksl-supplychain`](ksl-supplychain.md) *(experimental)* | **Multi-echelon supply-chain** domain layer |

These guides cross-reference each other in their §7 "See also"
sections — if the right view isn't obvious from the table, start with
`ksl-entity` and follow the pointers.

## Simulation optimization

| Guide | What it covers |
|---|---|
| [`ksl-simopt`](ksl-simopt.md) | `ksl.simopt` and all of its sub-packages: problems, the evaluator/oracle, every solver (full parameter reference), trackers, caches, and an orientation to the benchmark harness |
| [`ksl-simopt-benchmark`](ksl-simopt-benchmark.md) | Benchmarking simopt solvers in depth: the `ksl.simopt.benchmark` harness (problems × solver cases × macro-reps under equal budgets), the synthetic problem ladder, the results database and analysis feeds, and the pilot-study walkthrough |

## Running as a server

Drive your models from outside the JVM — an AI assistant over MCP, or
scripts and web apps over REST.

| Guide | What it covers |
|---|---|
| [`ksl-server`](ksl-server.md) | Running KSL's capabilities as MCP / REST servers — transports, distributions, the bundle drop-in, securing cross-machine access, and day-2 operation. Operational (shell / HTTP / config), not compile-verified Kotlin. |
| [`apps/mcp-server`](apps/mcp-server.md) | **Getting started** with the MCP server for an AI assistant (Claude Desktop, Cursor, Codex) — build, connect a client, and a first tool-driven session with real transcripts. The user-facing companion to `ksl-server`. |

## Random numbers, distributions, statistics

| Guide | What it covers |
|---|---|
| [`ksl-utilities-random`](ksl-utilities-random.md) | RNGs, `RVariableIfc`, `StreamProviderIfc`, stream-number conventions |
| [`ksl-utilities-distributions`](ksl-utilities-distributions.md) | The distribution catalog and `CDFIfc` |
| [`ksl-utilities-distributions-fitting`](ksl-utilities-distributions-fitting.md) | PDF / distribution fitting |
| [`ksl-utilities-statistic`](ksl-utilities-statistic.md) | `Statistic`, `WeightedStatistic`, `TimeWeighted`, batch-means |

## I/O, reporting, decision analysis

| Guide | What it covers |
|---|---|
| [`ksl-utilities-io`](ksl-utilities-io.md) | The report DSL, CSV / Markdown / HTML output |
| [`ksl-utilities-moda`](ksl-utilities-moda.md) | Multi-objective decision analysis |
| [`ksl-utilities-misc`](ksl-utilities-misc.md) | Remaining `ksl.utilities` subpackages (batched coverage) |

---

## Conventions

- All guides follow the same 7-section template.
- Status banners (e.g. *experimental*) appear near the top when an
  API may change between releases.
- Code snippets are adapted from real, runnable examples under
  `KSLExamples` — the prevailing convention, since the cited file
  compiles on every normal build. A few older guides instead host
  snippets verbatim in a compile-only `doc/` package under a module's
  own `src/test` (e.g. `ksl.modeling.agent.doc`). Either way, a build
  break in the cited source is a guide-doc break.
- Cross-references between guides use plain `ksl-XXX.md` filenames so
  they keep working regardless of where the docs directory lives in
  the rendered output.
