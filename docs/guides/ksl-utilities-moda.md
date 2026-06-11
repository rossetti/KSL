# Guide: `ksl.utilities.moda`

A task-oriented guide to Multiple-Objective Decision Analysis (MODA) in the KSL:
scoring alternatives on several metrics, transforming those scores into values,
and combining them into an overall ranking.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
  Use it when you need the exact signature of a method.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** covers the broader
  simulation-analysis context.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from the in-package demo
`KSLCore/.../ksl/utilities/moda/TestModa.kt` and the example demos
`KSLExamples/.../ksl/examples/general/utilities/TestMODAAnalyzer.kt` and
`KSLExamples/.../ksl/examples/general/reporting/MODAReportingDemo.kt`.

---

## 1. What this package is for

`ksl.utilities.moda` implements **Multiple-Objective Decision Analysis** — a
framework for choosing among competing alternatives when there is more than one
objective and the objectives conflict.

The motivating problem: you have several **alternatives** (simulation
configurations, candidate distributions, design options) and several **metrics**
on which to judge them (cost, utilization, system time, goodness-of-fit). The
metrics are measured in different units and pull in different directions —
*bigger is better* for some, *smaller is better* for others. MODA gives you a
principled way to:

1. **Score** each alternative on each metric.
2. **Transform** each raw score onto a common 0–1 *value* scale via a value
   function (so units and directions become comparable).
3. **Weight and combine** the per-metric values into a single overall value per
   alternative, then **rank** them.

It is used inside the KSL itself — `PDFModeler` uses an `AdditiveMODAModel` to
rank candidate distributions — and you can use it directly to compare simulation
experiments.

### How it relates to its neighbors

| Package | Role |
|---|---|
| `ksl.utilities.statistic` | Produces the raw responses/scores that become MODA metrics. |
| `ksl.utilities.distributions.fitting` | A built-in *consumer* of MODA — scores distribution fits. |
| `ksl.utilities.io` (`dbutil`, `report`) | Supplies experiment data and renders MODA results to reports/databases. |
| `ksl.utilities.moda` | **Combines** multi-metric scores into an overall ranking (this package). |

---

## 2. The mental model

Four concepts form a pipeline. Once you see how they chain, the API follows.

```
   Metric (+ direction, domain)
        │  raw measurement
        ▼
   Score  ──ValueFunction──▶  value in [0,1]
                                   │  weighted sum across metrics
                                   ▼
                          overall value per Alternative  ──▶  ranking
```

1. **`Metric` / `MetricIfc`** — one objective. It has a `name`, a `domain`
   (`Interval`), and a `direction` (`BiggerIsBetter` or `SmallerIsBetter`,
   defaulting to smaller-is-better). The metric defines *what* you are measuring
   and *which way is good*.

2. **`Score`** — one alternative's raw measurement on one metric: a `(metric,
   value, valid)` triple.

3. **`ValueFunctionIfc`** — maps a `Score` to a value in [0, 1], normalizing
   units and honoring direction. `LinearValueFunction` is the default;
   `LogisticFunction` is an S-curve alternative.

4. **`MODAModel`** — the engine. It holds the metric→value-function map and the
   alternatives (each a list of `Score`s). `AdditiveMODAModel` is the concrete
   model: it computes each alternative's **overall value as a weighted sum** of
   its per-metric values.

Two consequences worth internalizing:

- **Two layers of input define a model:** first the *metrics and their value
  functions* (`defineMetrics` / the constructor's `metricDefinitions`), then the
  *alternatives and their scores* (`defineAlternatives`). You cannot define
  alternatives before metrics exist.
- **`MODAAnalyzer` is the high-level shortcut** for the common case: comparing
  KSL simulation experiments straight from a `KSLDatabase`, with metrics derived
  from named responses.

---

## 3. Quick start

Build an additive model by hand: two metrics, three alternatives, then rank.

```kotlin
import ksl.utilities.Interval
import ksl.utilities.moda.AdditiveMODAModel
import ksl.utilities.moda.LinearValueFunction
import ksl.utilities.moda.Metric
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.moda.ValueFunctionIfc

fun main() {
    // 1) define metrics (what we measure + which direction is good)
    val cost = Metric("Cost", domain = Interval(0.0, 100.0))      // smaller is better (default)
    val throughput = Metric("Throughput", domain = Interval(0.0, 50.0))
    throughput.direction = MetricIfc.Direction.BiggerIsBetter

    // 2) attach a value function to each metric
    val metricDefs: Map<MetricIfc, ValueFunctionIfc> = mapOf(
        cost to LinearValueFunction(),
        throughput to LinearValueFunction()
    )

    // 3) create the additive model (equal weights by default)
    val model = AdditiveMODAModel(metricDefs)

    // 4) define alternatives by their raw scores on each metric
    model.defineAlternatives(
        mapOf(
            "Design A" to listOf(Score(cost, 40.0), Score(throughput, 30.0)),
            "Design B" to listOf(Score(cost, 25.0), Score(throughput, 20.0)),
            "Design C" to listOf(Score(cost, 60.0), Score(throughput, 45.0))
        )
    )

    // 5) read off overall values and rankings
    model.sortedMultiObjectiveValuesByAlternative().forEach { (name, value) ->
        println("%-10s overall value = %.4f".format(name, value))
    }
    println("Top: ${model.topAlternativesByMultiObjectiveValue()}")
}
```

---

## 4. How do I...?

### ...define metrics and their directions?

`Metric` takes a name and a domain; set `direction` when bigger is better.

```kotlin
import ksl.utilities.Interval
import ksl.utilities.moda.Metric
import ksl.utilities.moda.MetricIfc

val utilization = Metric("Utilization", domain = Interval(0.0, 1.0))
utilization.direction = MetricIfc.Direction.BiggerIsBetter   // default is SmallerIsBetter
```

### ...choose a value function?

`LinearValueFunction` maps scores linearly across the metric's domain.
`LogisticFunction` applies an S-shaped transform (useful when extreme values
should saturate).

```kotlin
import ksl.utilities.moda.LinearValueFunction
import ksl.utilities.moda.LogisticFunction

val linear = LinearValueFunction()
val logistic = LogisticFunction(location = 10.0, scale = 2.0)
```

For a fully custom transform, implement the single-method functional interface:

```kotlin
import ksl.utilities.moda.Score
import ksl.utilities.moda.ValueFunctionIfc

val custom = ValueFunctionIfc { score: Score -> /* return value in [0,1] */ score.value / 100.0 }
```

### ...set weights instead of using equal weights?

Pass a `metric -> weight` map to the constructor, or call `assignWeights`. The
model normalizes them.

```kotlin
import ksl.utilities.moda.AdditiveMODAModel

val model = AdditiveMODAModel(
    metricDefinitions = metricDefs,
    weights = mapOf(cost to 0.7, throughput to 0.3)
)
// or change them later:
model.assignWeights(mapOf(cost to 0.5, throughput to 0.5))
```

### ...add the alternatives to score?

Each alternative is a name mapped to a list of `Score`s — one score per metric.
Metrics must already be defined.

```kotlin
model.defineAlternatives(
    mapOf(
        "Two Workers"   to listOf(Score(cost, 40.0), Score(throughput, 30.0)),
        "Three Workers" to listOf(Score(cost, 55.0), Score(throughput, 42.0))
    )
)
```

### ...get the overall values and the ranking?

```kotlin
val overall: Map<String, Double> = model.multiObjectiveValuesByAlternative()
val sorted: List<Pair<String, Double>> = model.sortedMultiObjectiveValuesByAlternative()
val ranks: Map<String, Int> = model.alternativeRankedByMultiObjectiveValue()
val winners: Set<String> = model.topAlternativesByMultiObjectiveValue()

// the overall value for one alternative
val v = model.multiObjectiveValue("Three Workers")
```

### ...inspect the intermediate scores, values, and ranks as data frames?

`MODAModel` exposes the whole computation as Kotlin DataFrames.

```kotlin
val scoresDf  = model.alternativeScoresAsDataFrame("Alternatives")   // raw scores
val valuesDf  = model.alternativeValuesAsDataFrame("Alternatives")   // transformed values
val ranksDf   = model.alternativeRanksAsDataFrame()                  // per-metric ranks
val resultsDf = model.alternativeResultsAsDataFrame("Alternatives")  // combined
println(scoresDf)
```

### ...rank by first-place finishes instead of overall value?

Besides the weighted-value ranking, the model can rank by how often an
alternative comes first across metrics.

```kotlin
val firstRankCounts = model.alternativeFirstRankCounts()
val byFirstRank: Set<String> = model.topAlternativesByFirstRankCounts()
```

### ...compare KSL simulation experiments directly (the high-level path)?

`MODAAnalyzer` builds and runs a per-replication MODA analysis straight from
within-replication data captured in a `KSLDatabase`. Describe each response with
a `MODAAnalyzerData` entry.

```kotlin
import ksl.utilities.Interval
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.moda.MODAAnalyzer
import ksl.utilities.moda.MODAAnalyzerData
import ksl.utilities.moda.MetricIfc
import ksl.simulation.Model

// run two experiments, capturing results to a KSLDatabase
val model = Model("MODA Analyzer Testing")
model.numberOfReplications = 10
val dbObserver = KSLDatabaseObserver(model)
val db = dbObserver.db
// ... configure and simulate the alternatives, naming each experiment ...

val alternativeNames = setOf("Two Workers", "Three Workers")
val responseDefinitions = setOf(
    MODAAnalyzerData("Worker Utilization", MetricIfc.Direction.BiggerIsBetter, domain = Interval(0.0, 1.0)),
    MODAAnalyzerData("System Time"),            // smaller is better, default value function
    MODAAnalyzerData("Total Processing Time")
)

val analyzer = MODAAnalyzer(alternativeNames, responseDefinitions, db.withinRepViewData())
analyzer.analyze()
analyzer.resultsAsDatabase("MODA_Analyzer_Results.db")   // persist results
```

### ...report MODA results?

`AdditiveMODAModel` integrates with the `ksl.utilities.io.report` DSL via
`toReport(...)`, which renders to browser/HTML/Markdown.

```kotlin
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown

model.toReport("MODA Evaluation").showInBrowser()
model.toReport("MODA Evaluation").writeHtml()
model.toReport("MODA Evaluation").writeMarkdown()
```

### ...where do the scores come from in practice?

A common pattern (used by `PDFModeler`) is to let another component produce the
evaluation model for you. `PDFModeler.evaluateScoringResults(...)` returns a
ready-built `AdditiveMODAModel` whose alternatives are candidate distributions:

```kotlin
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.random.rvariable.ExponentialRV

val data = ExponentialRV(10.0).sample(1000)
val modeler = PDFModeler(data)
val model = modeler.estimateAndEvaluateScores().evaluationModel  // an AdditiveMODAModel
println(model.alternativeValuesAsDataFrame("Distributions"))
```

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

| Type | Role |
|---|---|
| `MetricIfc` / `Metric` | One objective: `name`, `domain`, `direction` (`BiggerIsBetter`/`SmallerIsBetter`). |
| `Score` | An alternative's raw value on a metric: `(metric, value, valid)`. |
| `ValueFunctionIfc` | Maps a `Score` to a [0,1] value. Functional interface — easy to customize. |
| `LinearValueFunction` | The default linear score→value transform. |
| `LogisticFunction` | An S-curve value function (`location`, `scale`). |
| `MultiAttributeValueFunctionIfc` | The contract for combining per-metric values into an overall value. |
| `MODAModel` | Abstract engine: holds metrics + alternatives; exposes scores/values/ranks as data frames. |
| `AdditiveMODAModel` | Concrete model: overall value = weighted sum of per-metric values. |
| `MODAAnalyzer` | High-level driver: per-replication MODA over `KSLDatabase` experiment data. |
| `MODAAnalyzerData` | Describes one response for `MODAAnalyzer` (`responseName`, `direction`, `weight`, `valueFunction`, `domain`). |

---

## 6. Gotchas and best practices

- **Define metrics before alternatives.** `defineAlternatives` throws if no
  metrics have been defined — the metric→value-function map must exist first
  (via the constructor or `defineMetrics`).

- **Get the direction right.** The default is `SmallerIsBetter`. For
  utilization, throughput, or goodness-of-fit correlations, set
  `direction = BiggerIsBetter`, or the ranking will be inverted.

- **Each alternative needs a score for each metric.** The `Score` list per
  alternative should cover every defined metric; mismatches produce invalid or
  missing values.

- **Domains matter for linear value functions.** `LinearValueFunction` scales a
  score across the metric's `domain` interval. By default `Metric` allows the
  model to rescale domains to the realized scores (`allowRescalingByMetrics` in
  `defineAlternatives`); disable it if you want fixed, externally-meaningful
  scales.

- **Weights are normalized.** You can pass un-normalized weights; the additive
  model normalizes them. Equal weights are the default.

- **Two ranking notions exist.** Overall weighted value
  (`topAlternativesByMultiObjectiveValue`) and first-rank frequency
  (`topAlternativesByFirstRankCounts`) can disagree — choose the one that matches
  your decision rule, or report both.

- **`MODAAnalyzer` needs simulation data.** It consumes
  `KSLDatabase.withinRepViewData()`, so capture results with a
  `KSLDatabaseObserver` and run your experiments first.

---

## 7. See also

- **The data being scored:** `ksl.utilities.statistic` responses and
  `ksl.utilities.distributions.fitting` (which builds an `AdditiveMODAModel`
  internally via `PDFModeler.evaluateScoringResults`).
- **Experiment data & reporting:** `ksl.utilities.io.dbutil`
  (`KSLDatabase`/`KSLDatabaseObserver`) and `ksl.utilities.io.report`
  (`toReport`, `writeHtml`, `writeMarkdown`).
- **Runnable examples:** `ksl/utilities/moda/TestModa.kt` (in `KSLCore`),
  `TestMODAAnalyzer.kt` and `MODAReportingDemo.kt` (in `KSLExamples`).
- **Theory and workflow:** the [KSL Book](https://rossetti.github.io/KSLBook/).
