# KSL usage guides

Task-oriented guides covering the major KSL packages. Most follow the
same 7-section template (overview, mental model, quick start, recipes,
key types, gotchas, see also); a few — where a package's scope genuinely
doesn't fit seven sections — extend it, and say so near the top.
Library-guide code snippets are compile-verified against the source on
every build.

> **Looking for the desktop apps?** Step-by-step, student-facing user guides for the KSL
> desktop applications — [Single](apps/single.md), [Scenario](apps/scenario.md),
> [Experiment](apps/experiment.md), [Simopt](apps/simopt.md), [Animation](apps/animation.md),
> [Results](apps/results.md), [Distribution](apps/distribution.md), and
> [Bundle Workbench](apps/bundle-workbench.md) — plus the [`kslpkg`](apps/bundle-tools.md) CLI
> and the [KSL Server](apps/ksl-server.md) live under [`apps/`](apps/README.md). Those are
> hands-on walkthroughs; the guides below are developer/library references.

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
| [`ksl-simopt-tutorial`](ksl-simopt-tutorial.md) | **Start here.** A hands-on, step-by-step tutorial for undergraduates new to the KSL: three worked simulation-optimization problems (a noisy math function, a constrained discrete-event inventory model, and a maximizing newsvendor) taken all the way from mathematical statement through a running solver to a benchmark study, with runnable companion code |
| [`ksl-simopt`](ksl-simopt.md) | `ksl.simopt` and all of its sub-packages: problems, the evaluator/oracle, every solver (full parameter reference), trackers, caches, and an orientation to the benchmark harness |
| [`ksl-simopt-benchmark`](ksl-simopt-benchmark.md) | Benchmarking simopt solvers in depth: the `ksl.simopt.benchmark` harness (problems × solver cases × macro-reps under equal budgets), the synthetic problem ladder, the results database and analysis feeds, and the pilot-study walkthrough |

## Running as a server

Drive your models from outside the JVM with an AI assistant over MCP. The **KSL Server**
gives an assistant searchable, tool-driven access to all three surfaces of KSL — running
models, the source code, and the textbook — from one process, with one-click setup and a
web console.

| Guide | What it covers |
|---|---|
| [`apps/ksl-server`](apps/ksl-server.md) | The **KSL Server** — install it, open the console, connect a client with one click, and run models, textbook search, and source search from your assistant. |

## Random numbers, distributions, statistics

| Guide | What it covers |
|---|---|
| [`ksl-utilities-random`](ksl-utilities-random.md) | RNGs, `RVariableIfc`, `StreamProviderIfc`, stream-number conventions |
| [`ksl-utilities-distributions`](ksl-utilities-distributions.md) | The distribution catalog and `CDFIfc` |
| [`ksl-utilities-distributions-fitting`](ksl-utilities-distributions-fitting.md) | PDF / distribution fitting |
| [`ksl-metalog`](ksl-metalog.md) | The metalog family: fitting, elicitation from quantiles, when not to use one |
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
