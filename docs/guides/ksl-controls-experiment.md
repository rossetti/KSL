# Guide: `ksl.controls.experiments`

A task-oriented guide to the KSL's **experiment** layer: the classes that take a
`Model` plus a set of named inputs and turn it into a *designed experiment*
(a grid of factor settings, fit to a regression) or a set of *scenarios*
(hand-picked input configurations run as separate experiments) — sequentially
or across a bounded pool of coroutines.

This guide complements, rather than replaces:

- The **API reference** (Dokka/KDoc) documents every member of every class.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)**, **Chapter 10**,
  covers the *theory* of experimental design, factorial/fractional designs,
  response surfaces, and multiple comparisons. This guide does not re-derive
  that material — it answers *"how do I drive this package from Kotlin?"*

> **Status.** The *design* classes (`Factor`, `FactorialDesign`,
> `CentralCompositeDesign`, `LinearModel`) and the *sequential* runners
> (`DesignedExperiment`, `ScenarioRunner`) are stable and track Chapter 10.
> The **parallel execution pathways** — `ParallelDesignedExperiment`,
> `ConcurrentScenarioRunner`, and the `ExecutionMode` enum — are newer and
> **evolving** *(experimental)*: signatures, callbacks, and defaults may change
> between releases.

> **Naming note.** The package on disk is `ksl.controls.experiments` (plural).
> This guide's filename uses the singular for consistency with the app guides.

The code shown below is adapted from real, runnable examples under
`KSLExamples` — primarily `DemoExperiments.kt` (design construction,
sequential experiments, regression) and the `ksl.examples.general.controls.experiments`
package (`DemoParallelDesignedExperiment.kt`, `DemoScenarios.kt`) for the
concurrent pathway and scenarios. Because these are ordinary KSLExamples
source files, they compile on every normal build — a build break there is a
break in this guide. See §7 for the complete file list.

---

## 1. What this package is for

`ksl.controls.experiments` sits directly on top of [`ksl.controls`](ksl-controls.md).
Controls give you *named, externally-settable model inputs*
(`"Worker.numWorkers"`, `"ServiceTimeRV.mean"`). This package is what *drives*
those inputs across many runs and collects the results.

Everything reduces to one primitive — **run this model with these inputs and
these run parameters, give me back a `SimulationRun`** — wrapped in four
progressively higher-level abstractions:

| Layer | Type(s) | What it adds |
|---|---|---|
| **Run engine** | `SimulationRunner`, `ConcurrentSimulationRunner` | Apply inputs to one model, run it, capture a `SimulationRun`. Synchronous vs. coroutine-aware. |
| **Design** | `Factor`, `FactorialDesign`, `TwoLevelFactorialDesign`, `CentralCompositeDesign`, `ExperimentalDesign`, `LinearModel` | Describe *what to vary* — a grid of `DesignPoint`s over factors, and the regression to fit. |
| **Designed experiment** | `DesignedExperiment`, `ParallelDesignedExperiment` | Bind factors → input keys, run every design point, expose data frames + regression. |
| **Scenarios** | `Scenario`, `ScenarioRunner`, `ConcurrentScenarioRunner` | Run a handful of named, hand-picked input configurations as separate experiments. |

### How it relates to its neighbors

| Package | Role |
|---|---|
| [`ksl.controls`](ksl-controls.md) | Source of the input keys this package sets — `model.controls()`, the `"Element.property"` convention. |
| [`ksl.simulation`](ksl-simulation.md) | `Model`, `ModelBuilderIfc`, `SimulationDispatcher`, `extractRunParameters()`/`changeRunParameters()`. |
| [`ksl.utilities.statistic`](ksl-utilities-statistic.md) | `Statistic`, `RegressionResultsIfc`, `OLSRegression` behind `regressionResults(...)`. |
| [`ksl.utilities.io.dbutil`](ksl-utilities-io.md) | `KSLDatabase` / `KSLDatabaseObserver` capture, `SnapshotBatchWriter` for the parallel commit phase. |
| **Desktop apps** | The [Experiment app](apps/experiment.md) wraps `ParallelDesignedExperiment`; the [Scenario app](apps/scenario.md) wraps `ConcurrentScenarioRunner`. |

---

## 2. The mental model

### 2.1 The two questions

Choosing a class is answering two orthogonal questions:

1. **What varies?**
   - A **design** — factors swept over a planned grid, so you can *fit a
     regression / response surface*. Use a `DesignedExperiment`.
   - A handful of **scenarios** — named, hand-picked input configurations you
     want to *compare*. Use a `ScenarioRunner`.
2. **How do the runs execute?**
   - **Sequentially**, reusing one `Model` instance.
   - **Concurrently**, each run on its own *fresh* `Model`, dispatched across a
     bounded coroutine pool.

That's a 2×2:

| | **Sequential** (one shared model) | **Concurrent** (fresh model per unit) |
|---|---|---|
| **Design** (grid → regression) | `DesignedExperiment` | `ParallelDesignedExperiment` |
| **Scenarios** (named configs) | `ScenarioRunner` | `ConcurrentScenarioRunner` |

All four sit on the same run engine and produce the same `SimulationRun`
objects, so the *analysis* surface (`simulationRuns`, `observationsAsMap`,
data frames, regression) is nearly identical across the row. The columns differ
only in *execution mechanics*.

### 2.2 The input pipeline

Every runner ultimately builds a `SimulationRun` and hands it to a
`SimulationRunner`, which stages three families of input onto the model and then
simulates:

```
factor level / scenario value
        │  (bound to a key: "MM1Q.numServers" or "MM1_Test:ServiceTime.mean")
        ▼
inputs: Map<String,Double>   stringInputs: Map<String,String>   jsonInputs: Map<String,String>
        │
        ▼
SimulationRunner.setupSimulation:  split keys into controls vs. RV parameters,
        stage them (deferred), changeRunParameters(...)
        ▼
model.simulate()  →  results captured into SimulationRun.results
                     (response name → DoubleArray, one value per replication)
```

Key facts that fall out of this design:

- **Input keys follow the controls convention.** A control is
  `"<elementName>.<property>"`; a random-variable parameter is
  `"<rvName>.<param>"` (the separator is `SimulationRunner.rvParamConCatChar`,
  `"."` by default — the *same* character controls use). At setup time each key
  is checked against controls **first**, then RV parameters. See
  [`ksl-controls`](ksl-controls.md) for how keys are formed and discovered.
- **Unknown keys throw at `simulate()` time, not silently.**
  `SimulationRunner.setupSimulation` rejects any key that matches neither a
  control nor an RV parameter with an `IllegalArgumentException`, for both the
  sequential and concurrent run engines. `DesignedExperiment`,
  `ParallelDesignedExperiment`, and the pre-built-model `Scenario`
  constructors validate keys even earlier, up front via
  `model.validateInputKeys(...)`, and fail fast at construction. (Builder-based
  `Scenario`s skip that construction-time check — see §6 — so a bad key there
  surfaces only once `simulate()` actually runs.)
- **A `SimulationRun` is a data-transfer object.** It is `@Serializable`, holds
  the inputs, the 15-field `ExperimentRunParameters`, and `results`, and offers
  `replicationObservations(name)`, `acrossReplicationStatistic(s)(...)`, and
  `statisticalReporter()` for analysis without touching a database.

### 2.3 The parallel execution pathway

The concurrent column (`ParallelDesignedExperiment`, `ConcurrentScenarioRunner`)
shares one execution shape. Internalize these five invariants and both classes
read the same:

1. **Model isolation is mandatory.** Each concurrent unit builds its *own* fresh
   `Model` via a `ModelBuilderIfc.build(...)` — never a shared instance. Sharing
   a model across coroutines is a data race. `ConcurrentScenarioRunner.addScenario`
   *enforces* this (it rejects `Scenario`s that wrap a pre-built model);
   `ParallelDesignedExperiment` requires a builder by *construction* but cannot
   prove your builder actually returns a fresh model — that is your contract
   (see `ModelBuilderIfc`'s "pure constructor" KDoc).

2. **Two phases: run-all, then commit.** Phase 1 launches every unit
   concurrently with `async(SimulationDispatcher.default)` inside a
   `supervisorScope`; each unit runs its model through a
   `ConcurrentSimulationRunner` and holds all database-bound data in memory
   (`InMemorySnapshotCollector`). Phase 2, after `awaitAll`, writes results to
   the `KSLDatabase` **one unit at a time** (`SnapshotBatchWriter`) in iterator
   order — serialising writes avoids concurrent SQLite access while keeping
   simulation time fully parallel.

3. **Parallelism is CPU-bounded.** `SimulationDispatcher.default` is
   `Dispatchers.IO.limitedParallelism(Runtime.availableProcessors())`. Simulations
   are CPU-bound; running more than one per core hurts throughput. You may
   replace `SimulationDispatcher.default` before launching, but it must stay
   bounded.

4. **Cancellation and failure are isolated but reported.** A single unit's
   `RuntimeException` is caught, recorded on its `SimulationRun.runErrorMsg`, and
   does **not** stop its siblings; that unit's completion callback receives a
   `null` snapshot. Per-unit cancellation (`cancelDesignPoint(id)` /
   `cancelScenario(name)`) is isolated by the `supervisorScope` and the cancelled
   unit is **not** committed to the database. Cancelling the *whole* enclosing
   scope propagates a `CancellationException` as normal.

5. **`suspend` in, determinism out.** `simulate`/`simulateAll` on the parallel
   classes are `suspend` functions — call them from a coroutine, or wrap in
   `runBlocking { }` for scripts and tests. Results are reproducible regardless
   of finish order because each unit's random-stream position is assigned *up
   front* by the stream policy (§4), not by wall-clock timing. By default
   `ParallelDesignedExperiment` reproduces the exact per-replication numbers a
   sequential `DesignedExperiment` would produce.

Underneath every unit, sequential or concurrent, is the same pair of engines —
`SimulationRunner` (synchronous) and `ConcurrentSimulationRunner` (`suspend`,
cooperative cancellation between replications). They deliberately share their
`setupSimulation`/`captureResults` code so input and result semantics cannot
drift between the two pathways.

---

## 3. Quick start

Bind factors to model input keys, build a design, and simulate every point
sequentially — the shortest path from a model to a fitted response.

```kotlin
// 1. Factors: each is a named list of levels on the original scale.
val servers = Factor("Servers", doubleArrayOf(1.0, 2.0, 3.0))
val meanST = Factor("MeanST", doubleArrayOf(0.5, 0.8))

// 2. Design: the 3 x 2 full-factorial grid of the factors.
val design = FactorialDesign(setOf(servers, meanST))

// 3. Bind each factor to a model input key (a control or an RV parameter).
val model = mm1ModelBuilder("MM1_Test").build()
val factorSettings = mapOf(
    servers to "MM1Q.numServers",             // integer control
    meanST to "MM1_Test:ServiceTime.mean"     // RV parameter
)

// 4. Run every design point sequentially on the one shared model.
val de = DesignedExperiment("Quick Start DOE", model, factorSettings, design)
de.simulateAll(numRepsPerDesignPoint = 10)

// 5. One SimulationRun per design point; pull an across-replication stat.
for (run in de.simulationRuns) {
    val st = run.acrossReplicationStatistic("System Time")
    println("${run.name}: avg System Time = ${st?.average}")
}
```

`mm1ModelBuilder` is any function returning a fresh `Model` (an M/M/c queue in
the compiled snippets). `DesignedExperiment` reuses the one model it is given,
running each of the six design points in turn.

---

## 4. How do I...?

### ...build the design families

`Factor` levels must be **strictly increasing**; the default levels are
`[-1.0, 1.0]`. `TwoLevelFactor(name, low, high)` is the two-level shorthand.

```kotlin
// Full two-level factorial (2^3 = 8 points)
val design = TwoLevelFactorialDesign(
    setOf(
        TwoLevelFactor("A", low = 5.0, high = 15.0),
        TwoLevelFactor("B", low = 2.0, high = 11.0),
        TwoLevelFactor("C", low = 6.0, high = 10.0),
    )
)
println(design.designPointsAsDataframe(coded = true))
```

A **fractional** design is an *iterator* over a full two-level design, filtered
by a defining relation (see Chapter 10 for the algebra); factor indices in the
relation are 1-based (`1 = A`, `2 = B`, …):

```kotlin
val design = TwoLevelFactorialDesign(
    setOf(
        TwoLevelFactor("A", 5.0, 15.0),
        TwoLevelFactor("B", 2.0, 11.0),
        TwoLevelFactor("C", 6.0, 10.0),
        TwoLevelFactor("D", 3.0, 9.0),
        TwoLevelFactor("E", 4.0, 16.0),
    )
)
// Defining relation I = ABD = ACE = BCDE  → a 2^(5-2) resolution-III design.
val relation = setOf(setOf(1, 2, 4), setOf(1, 3, 5), setOf(2, 3, 4, 5))
val itr = design.fractionalIterator(relation)
println("factors=${itr.numFactors} points=${itr.numPoints} fraction(p)=${itr.fraction}")
// Or, for the simple half fraction:
val half = design.halfFractionIterator(half = 1.0)
```

A **central composite** design augments a two-level design with center and axial
points for a quadratic response surface:

```kotlin
val factors = setOf(
    TwoLevelFactor("A", 5.0, 15.0),
    TwoLevelFactor("B", 2.0, 11.0),
    TwoLevelFactor("C", 6.0, 10.0),
)
val axial = CentralCompositeDesign.rotatableAxialSpacing(numFactors = 3)
val ccd = CentralCompositeDesign(TwoLevelFactorialDesign(factors), axialSpacing = axial)
```

For an arbitrary, hand-built point set, use `ExperimentalDesign`:

```kotlin
val a = Factor("A", doubleArrayOf(1.0, 5.0))
val b = Factor("B", doubleArrayOf(1.0, 7.0))
val design = ExperimentalDesign(setOf(a, b))
design.addDesignPoint(doubleArrayOf(1.0, 1.0), numReps = 5)
design.addDesignPoint(doubleArrayOf(5.0, 7.0), numReps = 5)
// enforceRange = false permits points outside the factor level range.
design.addDesignPoint(doubleArrayOf(9.0, 9.0), numReps = 5, enforceRange = false)
```

> Any of these designs plugs into either `DesignedExperiment` (sequential) or
> `ParallelDesignedExperiment` (concurrent) — they all implement
> `ExperimentalDesignIfc`.

### ...run all design points in parallel

`ParallelDesignedExperiment` needs a `ModelBuilderIfc` (a fresh model per point),
and its `simulate`/`simulateAll` are `suspend`:

```kotlin
val servers = TwoLevelFactor("Servers", 1.0, 2.0)
val meanST = TwoLevelFactor("MeanST", 0.5, 0.8)
val design = TwoLevelFactorialDesign(setOf(servers, meanST))

val pde = ParallelDesignedExperiment(
    name = "Parallel DOE",
    modelBuilder = mm1ModelBuilder("MM1_Test"),   // fresh model per point
    factorSettings = mapOf<Factor, String>(
        servers to "MM1Q.numServers",
        meanST to "MM1_Test:ServiceTime.mean"
    ),
    design = design
)
// suspend: must run inside a coroutine scope.
pde.simulateAll(numRepsPerDesignPoint = 10)
println("ran ${pde.numSimulationRuns} design points")
```

Wrap the whole thing in `runBlocking { }` for a script or test. Note the
explicit `mapOf<Factor, String>(...)` type argument: `TwoLevelFactor` is a
`Factor`, but `Map<TwoLevelFactor, String>` is not a `Map<Factor, String>`.

### ...choose the design-point random-stream policy (parallel only)

Because concurrent points can't simply "continue the stream" the way a single
reused model does, `ParallelDesignedExperiment` assigns stream positions
explicitly:

```kotlin
// Default: independent (non-overlapping) streams across points.
pde.useIndependentRandomStreams(startingStreamAdvance = 0)
// Or: common random numbers — every point starts at the same block.
pde.useCommonRandomNumbers()
```

With the default `INDEPENDENT_RANDOM_STREAMS` policy and no fixed spacing, each
point advances by the cumulative replication count of the prior points (e.g. a
2² design at 3 reps/point gets advances `[0, 3, 6, 9]`), which reproduces the
sequential `DesignedExperiment` numbers exactly. `useCommonRandomNumbers()` sets
every advance to `0`. `ScenarioRunner`/`ConcurrentScenarioRunner` expose the same
idea per scenario via `useIndependentRandomStreams(...)` /
`Scenario.useStreamAdvance(n)`.

### ...show live progress and cancel one point (parallel only)

`cancelDesignPoint` only has anything to cancel if the run it's cancelling is
still in flight *concurrently* — launch `simulateAll` in its own coroutine
rather than awaiting it directly:

```kotlin
coroutineScope {
    launch {
        pde.simulateAll(
            numRepsPerDesignPoint = 5,
            onDesignPointStart = { dp -> println("start ${dp.number}") },
            onDesignPointComplete = { dp, snapshot ->
                println("done ${dp.number}: committed=${snapshot != null}")
            }
        )
    }
    // From another coroutine while the run is in flight:
    pde.cancelDesignPoint(1)
}
```

`snapshot` is `null` for a point that failed or was cancelled. A cancelled point
fires `onDesignPointCancelled`, is skipped in the database commit, and does not
appear in `simulationRuns`. `ConcurrentScenarioRunner` mirrors this with
`onScenarioStart` / `onScenarioComplete` / `cancelScenario(name)`.

### ...fit a regression to a response

Given a `de` (a `DesignedExperiment` or `ParallelDesignedExperiment`) that has
run, and its `design`:

```kotlin
// First-order + interactions + quadratics; coded (-1/+1) regression by default.
val lm = design.linearModel(type = LinearModel.Type.AllTerms)
val results = de.regressionResults("System Time", lm)
println(results)
```

`LinearModel.Type` is `FirstOrder`, `FirstAndSecond`, or `AllTerms`; you can also
build terms explicitly (`twoWay`, `quadratic`, `parseFromString("A B A*B")`, …).
`regressionResults` returns a `RegressionResultsIfc` (OLS) — render it with
`showResultsInBrowser()`. **Note the coded default:** `regressionResults(...)`
regresses on **coded** variables by default, but the data-frame extractors below
default to **raw** — see §6.

### ...pull results out

```kotlin
// Wide data frame: (point, exp_name, rep_id, <response>, factor1, ...).
val df = de.replicatedDesignPointsWithResponse("System Time", coded = true)
// Single response, its own data frame.
val rdf = de.responseAsDataFrame("System Time")
// Every executed response in one wide data frame (all response names by default).
val allDf = de.replicatedDesignPointsWithResponses()
// Every executed response, written to the model's csv directory.
de.resultsToCSV()
// Design-point label -> per-replication observations (box-plot ready).
val obs: Map<String, DoubleArray> = de.observationsAsMap("System Time")
```

`observationsAsMap` feeds `Statistic.boxPlotSummaries(...)` /
`MultiBoxPlot` directly. `responseAsDataFrame` and `replicatedDesignPointsWithResponses`
(plural — every response at once, `coded` defaulting to `false` like its singular
sibling) round out the extraction surface; see `DemoExperiments.kt` for all five
in use together. `ParallelDesignedExperiment` exposes the same methods.

### ...run a few named scenarios sequentially

```kotlin
// Legacy shared-model path: one model reused across scenarios.
val model = Model("MM1_Test")
model.numberOfReplications = 20
model.lengthOfReplication = 1000.0
model.lengthOfReplicationWarmUp = 200.0
GIGcQueue(model, numServers = 1, name = "MM1Q")

val runner = ScenarioRunner("Server Study")
runner.addScenario(model, name = "1 Server", inputs = mapOf("MM1Q.numServers" to 1.0))
runner.addScenario(model, name = "2 Servers", inputs = mapOf("MM1Q.numServers" to 2.0))
runner.addScenario(model, name = "3 Servers", inputs = mapOf("MM1Q.numServers" to 3.0))
runner.simulate()
runner.print()
```

Each scenario becomes a separately-named experiment in the runner's
`KSLDatabase`. Scenario **names must be unique** — they are the experiment names.

### ...run named scenarios concurrently

Every scenario needs its own fresh model, so construct scenarios from a
`ModelBuilderIfc`. `ConcurrentScenarioRunner.simulate` is `suspend`:

```kotlin
val runner = ConcurrentScenarioRunner("Server Study (parallel)")
for (c in 1..3) {
    val scenario = Scenario(
        modelBuilder = mm1ModelBuilder("MM1_Test"),   // fresh model per run
        name = "$c Servers",
        inputs = mapOf("MM1Q.numServers" to c.toDouble()),
        numberReplications = 20,
        lengthOfReplication = 1000.0,
        lengthOfReplicationWarmUp = 200.0
    )
    runner.addScenario(scenario)   // rejects scenarios that reuse a model
}
runner.simulate()   // suspend; scenarios run concurrently by default
runner.print()
```

`addScenario` throws if you hand it a scenario built from a pre-built `Model`
(its `supportsConcurrentExecution` is `false`).

### ...run a single scenario, or drive the engine directly

A `Scenario` can run standalone:

```kotlin
val run: SimulationRun = scenario.simulate()
println(run.acrossReplicationStatistic("System Time")?.average)
```

And for one model + one set of inputs with no design/scenario machinery, use the
engine itself:

```kotlin
val runner = SimulationRunner(model)
val run = runner.simulate(
    inputs = mapOf("MM1Q.numServers" to 2.0),
    experimentRunParameters = model.extractRunParameters().copy(numberOfReplications = 10)
)
println("responses: ${run.responseNames}")
```

`SimulationRunner.chunkReplications(model, numReplications, size)` splits a
replication budget into equal chunks (with `startingRepId` / stream advances set
so the chunks reproduce a single contiguous run) — the basis for distributing
replications.

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**Run engine**

| Type | Role |
|---|---|
| `SimulationRunner(model)` | Synchronous. `simulate(modelIdentifier, inputs, stringInputs, jsonInputs, experimentRunParameters) → SimulationRun`. |
| `ConcurrentSimulationRunner(model)` | `suspend simulate(...)`; drives the replication loop manually with cooperative cancellation and per-replication callbacks. One per fresh model. |
| `SimulationRun` | `@Serializable` result DTO: `inputs`, `experimentRunParameters`, `results` (response → `DoubleArray`), `replicationObservations`, `acrossReplicationStatistic(s)`, `statisticalReporter`, `runErrorMsg`, `hasError`/`hasResults`. |
| `ExperimentRunParameters` | The 15-field run configuration (name, reps, length, warm-up, stream options, antithetic, advances, …). From `model.extractRunParameters()`. |
| `ExperimentRunDefaults` | The 12-field, model-intrinsic subset of the above (drops the run-identity fields); the run-parameter component of `ModelDescriptor`. |

**Designs**

| Type | Role |
|---|---|
| `Factor` / `TwoLevelFactor` | A named, strictly-increasing set of levels; coded↔raw conversion (`midPoint`, `halfRange`, `toCodedValue`). |
| `ExperimentalDesignIfc` | `Iterable<DesignPoint>`; `factors`, `factorNames`, `linearModel(type)`, `designPointsAsDataframe(coded)`, `toCodedValues`/`toOriginalValues`. |
| `FactorialDesign` | Full cartesian product of levels (≥2 factors); lazy point iterator. |
| `TwoLevelFactorialDesign` | Adds `halfFractionIterator(...)`, `fractionalIterator(relation, …)`. |
| `CentralCompositeDesign` | Two-level design + center + axial points; `rotatableAxialSpacing(...)`. |
| `ExperimentalDesign` | Arbitrary hand-added points via `addDesignPoint(...)`. |
| `DesignPoint` | One factor-setting row; `number`, `numReplications`, `values()`, `codedValues()`. |
| `LinearModel` | String spec of regression terms (`FirstOrder`/`FirstAndSecond`/`AllTerms`, `twoWay`, `quadratic`, `parseFromString`). |

**Designed experiments**

| Type | Role |
|---|---|
| `DesignedExperiment` | **Sequential.** Reuses one `Model`; `simulateAll(...)`/`simulate(iterator, …)` (not `suspend`); `regressionResults`, data frames, `resultsToCSV`. |
| `ParallelDesignedExperiment` | **Concurrent.** Needs a `ModelBuilderIfc`; `suspend simulateAll(...)`; stream policy (`useCommonRandomNumbers`/`useIndependentRandomStreams`); `cancelDesignPoint`; per-point callbacks. |
| `DesignedExperimentIfc` | Reporting surface shared by both: `design`, `simulationRuns`, `responseNames`, `observationsAsMap`, `regressionResults`. |
| `DesignPointRandomStreamPolicy` | `INDEPENDENT_RANDOM_STREAMS` (default) vs `COMMON_RANDOM_NUMBERS`. |

**Scenarios**

| Type | Role |
|---|---|
| `Scenario` | A model *specification* (builder + inputs + run parameters). `supportsConcurrentExecution`, `simulate()`, `useStreamAdvance`. |
| `ScenarioRunner` | **Sequential.** Runs a `List<Scenario>` to a shared `KSLDatabase`; `simulate(scenarios, clearAllData)` (not `suspend`). |
| `ConcurrentScenarioRunner` | **Concurrent.** `suspend simulate(...)`; two-phase run-then-commit; `cancelScenario`; requires builder-based scenarios. |
| `ScenarioModelConstructionMode` | `MODEL_BUILDER` (concurrent-safe) vs `REUSED_MODEL_INSTANCE` (sequential only). |
| `ExecutionMode` | `SEQUENTIAL` / `CONCURRENT`. Consumed as a parameter of `ConcurrentScenarioRunner.simulate`; also a persisted app-document field (see §6). |

**Supporting (from `ksl.simulation`)**

| Type | Role |
|---|---|
| `ModelBuilderIfc` | `build(modelConfiguration?, experimentRunParameters?) → Model`; must be a **pure, side-effect-free constructor** returning a fresh model. |
| `SimulationDispatcher.default` | Bounded (`availableProcessors`) dispatcher for all concurrent runs; replaceable, must stay bounded. |

---

## 6. Gotchas and best practices

- **Concurrent runners need a fresh model per unit.** Build scenarios and
  parallel experiments from a `ModelBuilderIfc` that returns a *new*
  `Model` each call. `ConcurrentScenarioRunner.addScenario` rejects
  pre-built-model scenarios, but `ParallelDesignedExperiment` trusts your
  builder — a builder that returns a cached model silently corrupts results.

- **The parallel entry points are `suspend`.** `ParallelDesignedExperiment`
  and `ConcurrentScenarioRunner` `simulate`/`simulateAll` must be called from a
  coroutine; wrap in `runBlocking { }` for CLI/tests. The sequential
  `DesignedExperiment`/`ScenarioRunner` equivalents are ordinary blocking calls.

- **`ExecutionMode` has two different defaults depending on how you get
  there.** A new app `RunConfiguration` document defaults to `SEQUENTIAL`, and
  `ScenarioOrchestrator.submit()` passes that document field straight into
  `ConcurrentScenarioRunner.simulate(...)`. But if you call
  `ConcurrentScenarioRunner.simulate(...)` directly from Kotlin (as in the
  recipes above), its own `executionMode` parameter defaults to **`CONCURRENT`**
  independently of the app-document default. Don't assume the document's
  `SEQUENTIAL` default applies when you're driving the runner API yourself.

- **`coded` defaults differ across sibling methods.** On `DesignedExperiment` /
  `ParallelDesignedExperiment`, the data-frame extractors
  (`replicatedDesignPointsWithResponse`, `replicatedDesignPointsAsDataFrame`,
  `replicatedDesignPointsWithResponses`) default `coded = false` (raw levels),
  but the regression path (`regressionResults`, `regressionData`,
  `regressionDataAsDataFrame`) defaults `coded = true`. Pass `coded` explicitly
  if you care which scale you get.

- **Input-key validation is asymmetric for scenarios — in *when*, not
  *whether*.** A `Scenario` built from a pre-built `Model` validates its input
  keys at construction and fails fast. A `Scenario` built from a
  `ModelBuilderIfc` skips that upfront check — an unknown or mistyped key is
  only discovered once `simulate()` actually runs the model, where
  `SimulationRunner` throws `IllegalArgumentException` rather than silently
  dropping it. Either way you find out, but builder-based (i.e.
  concurrent-capable) scenarios find out later, and mid-run rather than at
  setup. `DesignedExperiment`/`ParallelDesignedExperiment` validate factor
  settings up front and fail fast regardless.

- **Runs default to persisting — and clearing.** `DesignedExperiment`,
  `ScenarioRunner`, and `ConcurrentScenarioRunner` create a `KSLDatabase`
  (`<name>.db`) by default. Prior data is wiped on re-run, but *how* differs:
  `DesignedExperiment` attaches a `KSLDatabaseObserver` that clears the
  experiment's data before running (set at construction), whereas
  `ParallelDesignedExperiment`, `ScenarioRunner`, and `ConcurrentScenarioRunner`
  clear via a `clearAllData = true` argument on `simulate`/`simulateAll`. To skip
  the database entirely, pass `kslDb = null` where the parameter is nullable
  (`DesignedExperiment`, `ParallelDesignedExperiment`) — the scenario runners
  always take a non-null database. To *append* instead of overwrite, pass
  `clearAllData = false` **and** ensure every experiment name is unique, or the
  commit will error.

- **Name your model elements and random variables.** Input keys are
  `"Element.property"` / `"RV.param"`; unnamed elements get generated names, so a
  key like `"ServiceTimeRV.mean"` only works if you named the RV
  `"ServiceTimeRV"`. See [`ksl-controls`](ksl-controls.md).

- **The RV-parameter and control separators are the same `"."`.** At setup a key
  is matched against controls first, then RV parameters, so a control and an RV
  parameter that flatten to the same string are indistinguishable — keep names
  distinct.

- **Structural control setters should guard against a running model.** The
  standard idiom (`require(!model.isRunning)` in the setter) matters more here,
  since experiment runners rewrite controls between runs.

---

## 7. See also

- **Where the input keys come from:** [`ksl-controls`](ksl-controls.md) — the
  annotation system, `model.controls()`, and the `"Element.property"` key
  convention this package sets.
- **The run substrate:** [`ksl-simulation`](ksl-simulation.md) — `Model`,
  `ModelBuilderIfc`, `SimulationDispatcher`, `extractRunParameters()`.
- **Analyzing results:** [`ksl-utilities-statistic`](ksl-utilities-statistic.md)
  (`Statistic`, regression, multiple comparisons) and
  [`ksl-utilities-io`](ksl-utilities-io.md) (`KSLDatabase`, report rendering).
- **The desktop apps** that wrap these classes: the
  [Experiment app](apps/experiment.md) (→ `ParallelDesignedExperiment`) and the
  [Scenario app](apps/scenario.md) (→ `ConcurrentScenarioRunner`).
- **Optimizing** inputs rather than measuring their effect:
  [`ksl-simopt-benchmark`](ksl-simopt-benchmark.md).
- **Theory and workflow:** the [KSL Book](https://rossetti.github.io/KSLBook/),
  **Chapter 10** — designed experiments, fractional designs, and response
  surfaces.
- **Runnable, compiled examples**, all under `KSLExamples`: `DemoExperiments.kt`
  and `Ch10Example3.kt` (design construction, sequential `DesignedExperiment`,
  regression) in `ksl.examples.book.appendixD`; `DemoParallelDesignedExperiment.kt`
  and `DemoScenarios.kt` (the concurrent pathway, custom design points,
  sequential/concurrent/standalone scenarios) in
  `ksl.examples.general.controls.experiments`; `ScenarioRandomStreamExamples.kt`
  and `TestSimRunner.kt` in `ksl.examples.general.running` for stream behavior
  and the low-level engine, respectively.
