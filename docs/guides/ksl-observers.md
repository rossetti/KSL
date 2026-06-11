# Guide: `ksl.observers`

A task-oriented guide to **observing a running simulation** in the KSL: capturing
replication and experiment data, tracing responses over time, controlling run
length by precision, warm-up (Welch) analysis, and timing — all without
modifying your model logic.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** — especially
  Chapter 5 — develops these ideas with full worked examples.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from `KSLExamples/.../book/chapter5`.

> **Not to be confused with `ksl.utilities.observers`.** That package provides
> the *low-level* observer primitives (`Observable`, `ObserverIfc`, `Emitter`)
> covered in the utilities guide. **This** package (`ksl.observers`) is the
> *model-level* observer framework: classes that watch a simulation's model
> elements and collect simulation data. It is built on the lower-level pattern
> but operates on `ModelElement`s and the simulation lifecycle.

---

## 1. What this package is for

`ksl.observers` lets you **attach data collection and run control to a
simulation from the outside**. Your model element defines *what happens*; an
observer watches it run and *records or reacts*. This separation means you can
add tracing, capture per-replication data, drive sequential sampling, or perform
warm-up analysis without touching the model class.

The observers fall into a few roles:

| Role | Classes |
|---|---|
| **Capture across-replication / experiment data** | `ReplicationDataCollector`, `ExperimentDataCollector` |
| **Trace a response/counter over simulated time** | `ResponseTrace`, `ResponseTraceCSV`, `CounterTrace`, `CounterTraceCSV` |
| **Control run length by precision** | `AcrossReplicationHalfWidthChecker`, `AcrossReplicationRelativePrecisionChecker` |
| **Warm-up (initialization bias) analysis** | `welch.WelchFileObserver` and the Welch analyzers |
| **Variance reduction & batching** | `ControlVariateDataCollector`, `BatchStatisticObserver` |
| **Timing & CSV reports** | `SimulationTimer`, `textfile.CSV*Report` |
| **Custom** | `ModelElementObserver` (the base class) |

### How it relates to its neighbors

| Package | Role |
|---|---|
| `ksl.simulation` | Source of the lifecycle events observers react to (`Model`, `ModelElement`). |
| `ksl.modeling.variable` | The `Response`/`TWResponse`/`Counter` elements most observers watch. |
| `ksl.utilities.observers` | The low-level `Observable`/`ObserverIfc` pattern this builds on. |
| `ksl.utilities.io` (`dbutil`, `plotting`, `report`) | Where observed data is stored (`KSLDatabaseObserver`), plotted (`WelchPlot`), and reported. |

---

## 2. The mental model

### `ModelElementObserver` + the lifecycle

Every observer here extends `ModelElementObserver`. It is the model-level mirror
of the `ModelElement` lifecycle from the `ksl.simulation` guide: you override the
moments you care about, and the framework calls them as the simulation runs.

| Hook | Called... |
|---|---|
| `beforeExperiment(me)` | Once, before all replications. |
| `beforeReplication(me)` / `initialize(me)` | At the start of each replication. |
| `update(me)` | Whenever the observed element changes (e.g. a response is assigned). |
| `warmUp(me)` | At the warm-up point each replication. |
| `timedUpdate(me)` | At regular timed-update intervals. |
| `replicationEnded(me)` / `afterReplication(me)` | At the end of each replication. |
| `afterExperiment(me)` | Once, after all replications. |

### Attach an observer to a model element

An observer watches a specific `ModelElement`. You attach it with:

```kotlin
modelElement.attachModelElementObserver(observer)   // and detach...(observer) later
```

**Most prebuilt collectors attach themselves for you.** You just construct them
with the thing to observe — e.g. `ResponseTrace(response)` and
`AcrossReplicationHalfWidthChecker(response)` self-attach in their constructors,
and `ReplicationDataCollector(model)` attaches to the responses you add. You only
call `attachModelElementObserver` directly for a custom observer.

### Observe-don't-modify

Observers are read-only with respect to model logic. They record data
(`ReplicationDataCollector`), write trace files (`ResponseTrace`), or *control
the run* (a half-width checker can stop replications early) — but they never
change how the model computes. Create them before `simulate()`.

---

## 3. Quick start

Attach a couple of observers to a model, run it, and read the captured data.

```kotlin
import ksl.observers.ReplicationDataCollector
import ksl.observers.ResponseTrace
import ksl.simulation.Model

fun main() {
    val model = Model("Pallet Processing")
    model.numberOfReplications = 10
    val pwc = PalletWorkCenter(model)

    // capture per-replication final values of specific responses
    val repData = ReplicationDataCollector(model)
    repData.addResponse(pwc.totalProcessingTime)

    // trace a response over simulated time (writes a trace file)
    val trace = ResponseTrace(pwc.numInSystem)

    model.simulate()

    println(repData)                       // the captured replication data
    println(repData.toDataFrame())         // ... as a Kotlin DataFrame
}
```

---

## 4. How do I...?

### ...capture per-replication results for analysis?

`ReplicationDataCollector` records the end-of-replication value of each response
(or counter) you add. Read it back as arrays, a map, or a data frame.

```kotlin
val repData = ReplicationDataCollector(model)
repData.addResponse(pwc.totalProcessingTime)
repData.addResponse(pwc.probOfOvertime)
repData.addCounterResponse(pwc.numProcessed)
// or grab everything: ReplicationDataCollector(model, addAll = true)

model.simulate()

val one: DoubleArray = repData.replicationData("Total Processing Time")
val all: Map<String, DoubleArray> = repData.allReplicationDataAsMap
val df = repData.toDataFrame()
```

### ...compare alternatives across experiments (multiple comparison)?

`ExperimentDataCollector` accumulates replication data across *named*
experiments, then hands you a `MultipleComparisonAnalyzer`.

```kotlin
import ksl.observers.ExperimentDataCollector

val expData = ExperimentDataCollector(model)

model.experimentName = "Two Workers"
model.simulate()

model.experimentName = "Three Workers"
pwc.numWorkers = 3
model.simulate()

val mcb = expData.multipleComparisonAnalyzerFor(
    listOf("Two Workers", "Three Workers"),
    pwc.totalProcessingTime.name
)
println(mcb)
```

### ...trace a response (or counter) over simulated time?

`ResponseTrace` records every change of a response with its time stamp — useful
for plotting how a quantity evolves within a replication. Cap the number of
replications traced to keep files small.

```kotlin
val trace = ResponseTrace(pwc.numInSystem)
trace.maxNumReplications = 1          // only trace the first replication
// ResponseTraceCSV / CounterTrace / CounterTraceCSV are analogous
```

### ...stop replications once a precision target is met (sequential sampling)?

Attach an `AcrossReplicationHalfWidthChecker` to a response and set the desired
half-width. It ends the experiment once the response's confidence-interval
half-width is small enough (set `numberOfReplications` to a large cap).

```kotlin
import ksl.observers.AcrossReplicationHalfWidthChecker

model.numberOfReplications = 10000          // an upper bound
val hwc = AcrossReplicationHalfWidthChecker(pwc.totalProcessingTime)
hwc.desiredHalfWidth = 5.0
model.simulate()                            // stops early when HW <= 5.0
```

`AcrossReplicationRelativePrecisionChecker` does the same against a relative
error target.

### ...analyze the warm-up period (Welch's method)?

Collect Welch data with a `WelchFileObserver`, then build an analyzer and plot to
find where the time series settles into steady state.

```kotlin
import ksl.observers.welch.WelchFileObserver
import ksl.utilities.io.plotting.WelchPlot

val rvWelch = WelchFileObserver(dtp.systemTime, batchSize = 1.0)      // observation-based
val twWelch = WelchFileObserver(dtp.numInSystem, batchSize = 10.0)    // time-weighted

model.simulate()

val analyzer = rvWelch.createWelchDataFileAnalyzer()
analyzer.createCSVWelchPlotDataFile()
WelchPlot(analyzer = analyzer).showInBrowser()
```

### ...capture all results into a database?

`KSLDatabaseObserver` (in `ksl.utilities.io.dbutil`, but used exactly like the
observers here) records every response/counter into a `KSLDatabase` as the model
runs.

```kotlin
import ksl.utilities.io.dbutil.KSLDatabaseObserver

val dbObserver = KSLDatabaseObserver(model)
model.simulate()
val df = dbObserver.db.acrossReplicationViewStatistics    // a DataFrame of results
```

### ...apply control-variate variance reduction?

`ControlVariateDataCollector` records a response together with one or more
control variates (random variables with known means) for post-run regression.

```kotlin
import ksl.observers.ControlVariateDataCollector

val cv = ControlVariateDataCollector(model)
cv.addResponse(pwc.totalProcessingTime)
cv.addControlVariate(pwc.serviceTimeRV, meanValue = 0.5)
model.simulate()
val data = cv.collectedDataAsMap()
```

### ...time how long a simulation takes?

```kotlin
import ksl.observers.SimulationTimer

val timer = SimulationTimer(model)
model.simulate()
// timer exposes per-replication elapsed times
```

### ...auto-write CSV reports?

The simplest path is the `Model` flag — it installs the `textfile.CSV*Report`
observers for you.

```kotlin
val model = Model("Pallet Processing", autoCSVReports = true)
// per-replication and across-experiment CSV files appear under the output directory
```

### ...write a custom observer?

Subclass `ModelElementObserver`, override the hooks you need, and attach it to a
concrete model element (e.g. a `Response` you hold inside your model element, or
the `Model` itself for model-level events).

```kotlin
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

class MyTracker : ModelElementObserver() {
    override fun afterReplication(modelElement: ModelElement) {
        println("replication finished at time ${modelElement.time}")
    }
}

model.attachModelElementObserver(MyTracker())   // Model is a ModelElement
```

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

| Type | Role |
|---|---|
| `ModelElementObserver` | Base class for all observers; override lifecycle hooks. |
| `ReplicationDataCollector` | Per-replication response/counter values → arrays, map, `toDataFrame()`. |
| `ExperimentDataCollector` | Cross-experiment data; `multipleComparisonAnalyzerFor(...)`. |
| `ResponseTrace` / `ResponseTraceCSV` | Time-stamped trace of a response within replications. |
| `CounterTrace` / `CounterTraceCSV` | The same for counters. |
| `AcrossReplicationHalfWidthChecker` | Stop replications when a half-width target is met. |
| `AcrossReplicationRelativePrecisionChecker` | Stop on a relative-precision target. |
| `BatchStatisticObserver` | Batch-means statistics on a response within one run. |
| `ControlVariateDataCollector` | Capture response + control variates for variance reduction. |
| `SimulationTimer` | Wall-clock timing of replications. |
| `welch.WelchFileObserver` + `welch.WelchDataFileAnalyzer` | Warm-up (Welch) data collection and analysis. |
| `textfile.CSVReplicationReport` / `CSVExperimentReport` | Auto CSV reports (via `Model(autoCSVReports = true)`). |
| `KSLDatabaseObserver` (in `io.dbutil`) | Capture all results into a `KSLDatabase`. |

---

## 6. Gotchas and best practices

- **Create observers before `simulate()`.** They must be attached before the run
  to capture it. Construct them right after building the model.

- **Most prebuilt collectors self-attach.** `ResponseTrace(response)`,
  `AcrossReplicationHalfWidthChecker(response)`, and `WelchFileObserver(response,
  ...)` attach in their constructors; `ReplicationDataCollector`/
  `ExperimentDataCollector` attach to what you add. You only call
  `attachModelElementObserver` for a *custom* `ModelElementObserver`.

- **You attach to a concrete `ModelElement`, not a `CIfc`.**
  `attachModelElementObserver` is a `ModelElement` method; the read-only
  `ResponseCIfc`/`CounterCIfc` views don't expose it. Hold the concrete
  `Response`/`Counter` (inside your model element) or attach to the `Model`.

- **Sequential sampling needs a high replication cap.** A half-width checker
  *stops* the run early; set `numberOfReplications` to an upper bound large
  enough that the precision target is the binding constraint.

- **Set `maxNumReplications` on traces.** `ResponseTrace` writes every change to a
  file; tracing all replications of a long run produces huge files. Limit it.

- **Welch `batchSize` differs by response type.** Use a small batch size for
  observation-based responses and a larger time batch for time-weighted ones, as
  the example does (`1.0` vs `10.0`).

- **Name experiments for cross-experiment analysis.** `ExperimentDataCollector`
  keys data by `experimentName`; set a distinct name before each `simulate()` so
  `multipleComparisonAnalyzerFor` can find them.

- **`KSLDatabaseObserver` lives in `io.dbutil`.** It behaves like the observers
  here but is imported from `ksl.utilities.io.dbutil`.

---

## 7. See also

- **The lifecycle observers react to:** `ksl.simulation` (`Model`,
  `ModelElement`).
- **What they observe:** `ksl.modeling.variable` (`Response`, `TWResponse`,
  `Counter`).
- **The low-level pattern underneath:** `ksl.utilities.observers`
  (`Observable`, `ObserverIfc`, `Emitter`).
- **Where observed data goes:** `ksl.utilities.io` — `KSLDatabaseObserver`
  (dbutil), `WelchPlot` (plotting), and the report framework.
- **Runnable examples:** `Ch5Example1` (replication/experiment collectors,
  tracing, database), `Ch5Example2` (sequential sampling), `Ch5Example4`
  (Welch analysis).
- **Theory and walkthroughs:** Chapter 5 of the
  [KSL Book](https://rossetti.github.io/KSLBook/).
