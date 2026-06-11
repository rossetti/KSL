# Guide: `ksl.utilities.statistic`

A task-oriented guide to collecting and summarizing statistics with the KSL.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
  Use it when you need the exact signature of a method.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** explains the
  statistical theory. This guide links to it instead of re-deriving formulas.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are drawn from
`KSLExamples/src/main/kotlin/ksl/examples/book/chapter3`.

---

## 1. What this package is for

`ksl.utilities.statistic` is the KSL's toolkit for **summarizing observed
data**. You feed it numbers (or booleans, or sampled outcomes) and it gives
back means, variances, confidence intervals, histograms, frequency tables,
box-plot summaries, and more.

It is most useful when you have a stream of values — typically generated from a
Monte Carlo experiment or a simulation — and you want statistically meaningful
summaries of them, including the half-widths and confidence intervals needed to
quantify estimation error.

### How it relates to its neighbors

These three packages are easy to confuse. They are distinct stages of a typical
workflow:

| Package | Role | Think of it as... |
|---|---|---|
| `ksl.utilities.random.rvariable` | **Generates** random values | The faucet — `NormalRV`, `ExponentialRV`, etc. produce observations. |
| `ksl.utilities.distributions` | **Evaluates** probability functions | The math — CDFs, PDFs, inverse CDFs, moments of a named distribution. |
| `ksl.utilities.statistic` | **Summarizes** observed values | The bucket and the ruler — collects what flowed out and measures it. |

A canonical loop ties them together: a random variable from `random.rvariable`
produces values, and a `Statistic` from this package collects them.

```kotlin
val n = NormalRV(20.0, 4.0, streamNum = 3)   // random.rvariable: the faucet
val stat = Statistic("Normal Stats")          // statistic: the bucket
for (i in 1..100) {
    stat.collect(n.value)                     // generate, then collect
}
println(stat)
```

---

## 2. The mental model

Almost everything in the package is built on a small set of abstractions.
Understanding these four ideas lets you predict how any class in the package
behaves.

1. **Collectors take observations.** The `CollectorIfc` interface defines the
   overloaded `collect(...)` family. You can collect a `Double`, an `Int`, a
   `Long`, a `Boolean`, a whole `DoubleArray`, a `Collection<Double>`, or even a
   lambda `() -> Double`. Every collecting class accepts all of these, so the
   way you *feed* data is uniform across the package.

2. **`collect(Boolean)` is how you estimate probabilities.** Collecting `true`
   records a `1.0` and `false` records a `0.0`, so the average of an indicator
   becomes an estimated probability. This is the idiom you will use constantly:

   ```kotlin
   val pGT20 = Statistic("P(X>=20)")
   pGT20.collect(x >= 20.0)   // average of these is the estimated P(X >= 20)
   ```

3. **Summaries are read off as properties.** Once data is collected, results are
   plain properties — `average`, `variance`, `standardDeviation`, `count`,
   `min`, `max`, `sum`, `halfWidth`, `confidenceInterval`, `skewness`,
   `kurtosis`, and so on (see `StatisticIfc` and `SummaryStatisticsIfc`). There
   is no separate "compute" step; reading the property reports the current
   state.

4. **Statistics are mutable and reusable.** A collector accumulates across all
   `collect` calls until you call `reset()`. The same instance is meant to be
   reused across the iterations of a loop, not re-created each time.

The class hierarchy mirrors this: `Collector` / `CollectorIfc` at the base,
`AbstractStatistic` adding the statistical contract (`StatisticIfc`), and
concrete classes (`Statistic`, `BatchStatistic`, `WeightedStatistic`, ...)
specializing the behavior.

---

## 3. Quick start

The shortest complete program: generate 100 normals, summarize them, and print
a formatted report.

```kotlin
import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.Statistic

fun main() {
    // a normal random variable: mean = 20.0, variance = 4.0, on stream 3
    val n = NormalRV(20.0, 4.0, streamNum = 3)

    val stat = Statistic("Normal Stats")
    val pGT20 = Statistic("P(X>=20)")

    for (i in 1..100) {
        val x = n.value
        stat.collect(x)              // collect the value
        pGT20.collect(x >= 20.0)     // collect an indicator -> estimates a probability
    }

    println(stat)                    // full summary of the Statistic
    println(pGT20)

    // pretty-printed, confidence-interval-oriented report for several statistics
    val reporter = StatisticReporter(mutableListOf(stat, pGT20))
    println(reporter.halfWidthSummaryReport())
}
```

`println(stat)` prints the complete summary; `StatisticReporter` lines several
statistics up in a single table with their averages and half-widths.

---

## 4. How do I...?

### ...estimate a mean and get a confidence interval?

Collect the values and read the properties. The confidence level defaults to
0.95 (changeable via `confidenceLevel`).

```kotlin
val stat = Statistic("Area Estimator")
for (i in 1..n) {
    stat.collect(someValue)
}
println("Average        = ${stat.average}")
println("Std. Dev.      = ${stat.standardDeviation}")
println("Half-width     = ${stat.halfWidth}")
println("CI             = ${stat.confidenceInterval}")
```

### ...estimate a probability?

Collect a boolean indicator; its average is the estimated probability.

```kotlin
val probOfWinning = Statistic("Prob of winning")
probOfWinning.collect(winner)        // winner: Boolean
// ...
println("P(win) estimate = ${probOfWinning.average}")
```

### ...decide how many observations I need?

Use the companion functions on `Statistic`. Run a small pilot to estimate the
standard deviation, then size the full run to a desired half-width.

```kotlin
// for a mean, given a standard-deviation estimate
val n = Statistic.estimateSampleSize(
    desiredHW = 0.5,
    stdDev = pilotStat.standardDeviation,
    level = 0.99
)

// for a proportion, given a point estimate of p
val m = Statistic.estimateProportionSampleSize(
    desiredHW = 0.05,
    pEst = pilotProb.average,
    level = 0.95
)
```

### ...build a histogram and view it?

Let the package recommend break points, add a catch-all upper bin, collect, and
plot.

```kotlin
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Histogram

val data = ExponentialRV(2.0, streamNum = 1).sample(1000)

var bp = Histogram.recommendBreakPoints(data)
bp = Histogram.addPositiveInfinity(bp)       // open-ended top bin for large values

val h = Histogram(breakPoints = bp)
h.collect(data)                              // collect the whole array at once
println(h)

h.histogramPlot().showInBrowser("Exponentially Distributed Data")
```

If you would rather not choose break points up front, `CachedHistogram` caches
observations and selects bins automatically:

```kotlin
import ksl.utilities.statistic.CachedHistogram

val ch = CachedHistogram()
ch.collect(data)
println(ch)
ch.histogramPlot().showInBrowser("Exponentially Distributed Data")
```

### ...tabulate frequencies of integers or labeled states?

For integers, use `IntegerFrequency`:

```kotlin
import ksl.utilities.random.rvariable.BinomialRV
import ksl.utilities.statistic.IntegerFrequency

val f = IntegerFrequency(name = "Frequency Demo")
f.collect(BinomialRV(0.5, 100, streamNum = 2).sample(10000))
println(f)
f.frequencyPlot().showInBrowser("Frequency Demo Plot")
```

For named/labeled states (and single-step transition counts between them), use
`StateFrequency`:

```kotlin
import ksl.utilities.statistic.StateFrequency

val sf = StateFrequency(6)            // six labeled states
val states = sf.states
for (i in 1..10000) {
    sf.collect(KSLRandom.randomlySelect(states))
}
println(sf)
sf.frequencyPlot(proportions = true).showInBrowser("State Frequency Demo Plot")
```

### ...get a five-number summary / box plot?

`BoxPlotSummary` computes the quartiles, fences, and inter-quartile range from a
data array.

```kotlin
import ksl.utilities.statistic.BoxPlotSummary

val bps = BoxPlotSummary(data, name = "My Data")
println("median   = ${bps.median}")
println("Q1, Q3   = ${bps.firstQuartile}, ${bps.thirdQuartile}")
println("IQR      = ${bps.interQuartileRange}")
println("min, max = ${bps.min}, ${bps.max}")
```

### ...handle correlated output from a single long run (batching)?

`BatchStatistic` forms batches of observations and computes statistics on the
batch means, which reduces the autocorrelation that biases naive confidence
intervals for within-run data.

```kotlin
import ksl.utilities.statistic.BatchStatistic

// args: minimum number of batches, minimum batch size, max-batches multiple
val bm = BatchStatistic(40, 25, 2)
for (i in 1..1000) {
    bm.collect(d.value)
}
println(bm)
val batchMeans = bm.batchMeans          // the batch-mean array
val reformed = bm.reformBatches(10)     // re-batch down to 10 batches
println(Statistic(reformed))            // summarize the reformed batches
```

### ...quantify estimation error without distributional assumptions (bootstrap)?

`Bootstrap` resamples your original data to estimate the sampling distribution
of an estimator (the mean by default).

```kotlin
import ksl.utilities.statistic.Bootstrap

val boot = Bootstrap(originalData = data)         // estimator defaults to Average()
boot.generateSamples(numBootstrapSamples = 1000)
println("bias estimate     = ${boot.bootstrapBiasEstimate}")
println("std-err estimate  = ${boot.bootstrapStdErrEstimate}")
println("percentile 95% CI = ${boot.percentileBootstrapCI()}")
```

### ...print several statistics in one report?

`StatisticReporter` (in `ksl.utilities.io`) formats a list of statistics. It can
emit plain text, Markdown, LaTeX, and data frames.

```kotlin
val reporter = StatisticReporter(mutableListOf(stat1, stat2))
reporter.addStatistic(stat3)                          // or add incrementally
println(reporter.halfWidthSummaryReport())            // plain text
println(reporter.halfWidthSummaryReportAsMarkDown())  // Markdown table
```

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**Workhorses — reach for these first**

| Type | Use it to... |
|---|---|
| `Statistic` | Collect summary statistics (mean, variance, CI, moments) on a stream of values. The default choice. |
| `Histogram` / `CachedHistogram` | Tabulate and plot the distribution of continuous data. `Cached` chooses bins for you. |
| `IntegerFrequency` | Tabulate counts/proportions of integer outcomes. |
| `StateFrequency` | Tabulate counts/proportions of labeled states and their transitions. |
| `BoxPlotSummary` | Compute quartiles, IQR, and fences from a data array. |

**Specialized estimators**

| Type | Use it to... |
|---|---|
| `BatchStatistic` | Batch correlated within-run output for valid confidence intervals. |
| `WeightedStatistic` | Collect weighted observations via `collect(obs, weight)`. |
| `TimeWeightedStatistic` | Collect time-persistent (time-averaged) statistics over a value that changes at known times. |
| `Bootstrap` / `MultiBootstrap` | Bootstrap resampling for non-parametric error estimates. |
| `JackKnifeEstimator` | Jackknife bias/variance estimation. |
| `MultipleComparisonAnalyzer` | Multiple-comparison-with-the-best analysis across alternatives. |
| `OLSRegression` / `RegressionData` | Ordinary least squares regression. |

**Supporting interfaces** — `CollectorIfc` (the `collect` family),
`StatisticIfc` and `SummaryStatisticsIfc` (the result properties),
`EstimateIfc`, `MeanEstimateIfc`. Implement these when you build your own
collectors.

---

## 6. Gotchas and best practices

- **Reuse instances; don't re-create them in the loop.** A `Statistic`
  accumulates across `collect` calls. Create it before the loop and call
  `reset()` if you need to start over (e.g., between replications).

- **`collect(Boolean)` for probabilities.** Reaching for a hand-rolled counter
  to estimate a probability is a common mistake. Collect the indicator directly
  and read `average`.

- **Set the random stream for reproducibility.** The examples pass
  `streamNum = ...` to the random variables. Statistics are deterministic given
  their inputs, so reproducibility is governed by how you seed the *generators*
  feeding them (see `ksl.utilities.random`). Use distinct stream numbers for
  independent sources, as in the craps and activity-network examples.

- **Naive CIs on within-run data are too narrow.** Output collected
  observation-by-observation within a single simulation run is autocorrelated,
  which makes the ordinary `confidenceInterval` optimistic. Use `BatchStatistic`
  (or across-replication statistics) instead. See the KSL Book's output-analysis
  chapter.

- **Add an open-ended top bin to histograms.** `Histogram.addPositiveInfinity`
  prevents large outliers from falling outside the defined break points. There
  is a corresponding helper for the lower tail.

- **`collect` is overloaded — let it do the work.** You rarely need to convert
  types before collecting; pass the `DoubleArray`, `IntArray`, `BooleanArray`,
  or `Collection<Double>` directly.

---

## 7. See also

- **Runnable examples:** `KSLExamples/src/main/kotlin/ksl/examples/book/chapter3`
  — every snippet above is adapted from a numbered example there.
- **Reporting:** `ksl.utilities.io.StatisticReporter` for formatted output.
- **Generating data:** `ksl.utilities.random` (random number streams) and
  `ksl.utilities.random.rvariable` (random variables).
- **Distribution math:** `ksl.utilities.distributions` for CDFs, PDFs, and
  inverse CDFs; `ksl.utilities.distributions.fitting` for fitting distributions
  to data.
- **Theory and worked walkthroughs:** the
  [KSL Book](https://rossetti.github.io/KSLBook/), especially the Monte Carlo
  and output-analysis chapters.
