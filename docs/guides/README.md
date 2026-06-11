# KSL usage guides

Sixteen task-oriented guides, one per major KSL package. Each follows
the same 7-section template (overview, mental model, quick start,
recipes, key types, gotchas, see also). Code snippets are
compile-verified against the source on every build.

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
- Code snippets are hosted verbatim in compile-only test sources
  under `KSLTesting/src/test/kotlin/.../doc/`; a build break there is
  a guide-doc break.
- Cross-references between guides use plain `ksl-XXX.md` filenames so
  they keep working regardless of where the docs directory lives in
  the rendered output.
