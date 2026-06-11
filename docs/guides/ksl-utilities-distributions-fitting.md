# Guide: `ksl.utilities.distributions.fitting`

A task-oriented guide to fitting probability distributions to data and testing
goodness of fit with the KSL.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
  Use it when you need the exact signature of a method.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** explains the
  statistical theory of input modeling, estimation, and goodness-of-fit testing.
  This guide links to it instead of re-deriving the methods.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from
`KSLExamples/src/main/kotlin/ksl/examples/book/chapter2` (examples 2.23–2.27)
and `KSLExamples/src/main/kotlin/ksl/examples/book/appendixB`.

---

## 1. What this package is for

`ksl.utilities.distributions.fitting` is the KSL's **input-modeling** toolkit.
Given a sample of observed data, it answers the practitioner's central question:
*"which probability distribution should I use to represent this input, and how
good is the fit?"*

It does three things:

1. **Estimates parameters** of candidate distributions from data (MLE, method of
   moments, and other estimators).
2. **Scores and ranks** many candidate distributions automatically, so you can
   see which fits best.
3. **Tests goodness of fit** — chi-squared, Kolmogorov-Smirnov, Anderson-Darling
   — to quantify how well a chosen distribution matches the data.

It sits directly downstream of raw data and upstream of simulation: you fit a
distribution here, then use `distribution.randomVariable(...)` (from
`ksl.utilities.distributions` / `ksl.utilities.random`) to drive your model.

### How it relates to its neighbors

| Package | Role |
|---|---|
| `ksl.utilities.statistic` | Summarizes and visualizes the raw data (frequencies, histograms). |
| `ksl.utilities.distributions.fitting` | **Estimates, scores, and tests** candidate distributions (this package). |
| `ksl.utilities.distributions` | The distribution objects you end up fitting/selecting. |
| `ksl.utilities.random` | Generates from the chosen distribution in your simulation. |

---

## 2. The mental model

There are two parallel pipelines — one for **continuous** data and one for
**discrete** (count) data — built from the same three kinds of component.

```
                 estimator(s)            scoring/ranking            goodness-of-fit test
raw data ──▶ [parameter estimation] ──▶ [compare candidates] ──▶ [chi-sq / KS / AD]
```

| Component | Continuous | Discrete |
|---|---|---|
| **Modeler** (the high-level driver) | `PDFModeler` | `PMFModeler` |
| **Estimator** (one distribution's params) | `*MLEParameterEstimator`, `*MOMParameterEstimator`, ... (`ParameterEstimatorIfc`) | same family (e.g. `PoissonMLEParameterEstimator`) |
| **Goodness-of-fit test** | `ContinuousCDFGoodnessOfFit` | `DiscretePMFGoodnessOfFit`, `PoissonGoodnessOfFit` |

Three ideas tie it together:

1. **A `Modeler` is the easy path.** `PDFModeler(data)` / `PMFModeler(data)`
   wrap the whole workflow: they hold the data and its `histogram`/`statistics`,
   run a set of estimators, and produce ranked results. Start here.

2. **An `EstimationResult` is what an estimator returns** for one distribution —
   the estimated `parameters` plus diagnostics. A `ScoringResult` wraps an
   `EstimationResult` together with the fitted `distribution`, its `name`, and
   the `scores` used for ranking.

3. **Scoring ranks candidates; goodness-of-fit tests judge a single choice.**
   The modeler scores many distributions and sorts them
   (`resultsSortedByScoring`); you then run a goodness-of-fit test on the winner
   to get formal p-values.

---

## 3. Quick start

The fastest end-to-end path for continuous data: let `PDFModeler` estimate and
score every candidate, take the best, and test it.

```kotlin
import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit
import ksl.utilities.distributions.fitting.PDFModeler

fun main() {
    val data: DoubleArray = /* your observed data */ doubleArrayOf()

    val modeler = PDFModeler(data)

    // estimate parameters for all candidate distributions and score them
    val results = modeler.estimateAndEvaluateScores()

    // candidates ranked best-first
    results.resultsSortedByScoring.forEach(::println)

    // take the top-ranked distribution and test its goodness of fit
    val top = results.resultsSortedByScoring.first()
    println("Recommended distribution: ${top.name}")

    val gof = ContinuousCDFGoodnessOfFit(
        data,
        top.distribution,
        numEstimatedParameters = top.numberOfParameters
    )
    println(gof)
}
```

---

## 4. How do I...?

### ...look at my data before fitting?

Good input modeling starts with visualization. Use `statistic` constructs (and
the `io.plotting` helpers) to inspect the data first.

```kotlin
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot

val modeler = PDFModeler(data)
println(modeler.histogram)               // a Histogram of the data
println(modeler.statistics)              // summary statistics

modeler.histogram.histogramPlot().showInBrowser()
ObservationsPlot(data).showInBrowser()   // time-series / run-order view
ACFPlot(data).showInBrowser()            // autocorrelation check (independence)
```

### ...fit and rank many continuous distributions automatically?

`estimateAndEvaluateScores()` runs the default set of estimators and scores the
results. The returned `PDFModelingResults` exposes the rankings.

```kotlin
val results = modeler.estimateAndEvaluateScores()

results.resultsSortedByScoring.forEach(::println)   // best-first
val best = results.topResultByScore                 // the single best by score
println(best.name)
```

### ...see the whole analysis in a browser report?

`PDFModeler` can render rich HTML summaries.

```kotlin
val results = modeler.showAllResultsInBrowser()             // estimation + scoring
modeler.showAllGoodnessOfFitSummariesInBrowser(results)     // GOF for each candidate
```

### ...estimate parameters for one specific distribution?

Use a concrete estimator object directly. Each implements
`ParameterEstimatorIfc.estimateParameters(data)` and returns an
`EstimationResult`.

```kotlin
import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.random.rvariable.NormalRV

val data = NormalRV(2.0, 5.0, streamNum = 1).sample(100)

val estimator = NormalMLEParameterEstimator
val result = estimator.estimateParameters(data)
println(result)
```

### ...test goodness of fit for a continuous distribution?

`ContinuousCDFGoodnessOfFit` computes chi-squared, K-S, and Anderson-Darling
statistics. Tell it how many parameters you estimated (it affects the degrees of
freedom).

```kotlin
import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit

val dist = Exponential(10.0)
val data = dist.randomVariable(3).sample(1000)

val gof = ContinuousCDFGoodnessOfFit(data, dist, numEstimatedParameters = 1)
println(gof)                              // full report
println(gof.chiSquaredTestStatistic)
println(gof.chiSquaredPValue)
```

### ...fit and test a discrete (count) distribution?

Use `PMFModeler` to estimate, then `DiscretePMFGoodnessOfFit` (or the
specialized `PoissonGoodnessOfFit`) to test. Discrete tests need break points;
the `PMFModeler` companion provides helpers.

```kotlin
import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.distributions.fitting.PoissonGoodnessOfFit
import ksl.utilities.distributions.fitting.estimators.PoissonMLEParameterEstimator

// counts: an IntArray of observed counts
val modeler = PMFModeler(counts)
val results = modeler.estimateParameters(setOf(PoissonMLEParameterEstimator))
val e = results.first()
println(e)

// pull the estimated mean and run a Poisson goodness-of-fit test
val mean = e.parameters!!.doubleParameter("mean")
val pf = PoissonGoodnessOfFit(counts.toDoubles(), mean = mean)
println(pf.chiSquaredTestResults())
```

For a non-Poisson discrete distribution, build break points explicitly and use
the general test:

```kotlin
import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
import ksl.utilities.distributions.fitting.PMFModeler

val dist = NegativeBinomial(0.2, numSuccesses = 4.0)
val data = dist.randomVariable(streamNumber = 3).sample(200)
val breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
val pf = DiscretePMFGoodnessOfFit(data, dist, breakPoints = breakPoints)
println(pf.chiSquaredTestResults())
```

### ...quantify uncertainty in my parameter estimates (bootstrap)?

`BootstrapSampler` (in `ksl.utilities.statistic`) resamples the data and re-runs
an estimator, giving a distribution of parameter estimates.

```kotlin
import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.statistic.BootstrapSampler

val bss = BootstrapSampler(data, NormalMLEParameterEstimator)
val estimates = bss.bootStrapEstimates(400)
estimates.forEach { println(it) }
```

`PDFModeler` also offers `bootStrapParameterEstimates(...)` and
`confidenceIntervalForMinimum(...)` for the same purpose.

### ...plot the fitted distribution against the data?

A `ScoringResult` can produce a fit plot directly.

```kotlin
val top = results.resultsSortedByScoring.first()
top.distributionFitPlot().showInBrowser("Recommended Distribution ${top.name}")
```

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**High-level drivers — start here**

| Type | Use it to... |
|---|---|
| `PDFModeler` | Fit, score, rank, and report continuous distributions for a data set. |
| `PMFModeler` | The same for discrete (count) data. |
| `PDFModelingResults` | Hold the ranked results: `resultsSortedByScoring`, `topResultByScore`, data-frame views. |

**Results**

| Type | Represents... |
|---|---|
| `EstimationResult` | One estimator's output: estimated `parameters` and diagnostics. |
| `ScoringResult` | A fitted distribution + its `name`, `scores`, `numberOfParameters`, and `distributionFitPlot()`. |

**Estimators (`fitting.estimators`)** — implement `ParameterEstimatorIfc`.
Continuous: `NormalMLEParameterEstimator`, `ExponentialMLEParameterEstimator`,
`GammaMLEParameterEstimator` / `GammaMOMParameterEstimator`,
`WeibullMLEParameterEstimator` / `WeibullPercentileParameterEstimator`,
`LognormalMLEParameterEstimator`, `BetaMOMParameterEstimator`,
`PearsonType5MLEParameterEstimator`, `LaplaceMLEParameterEstimator`,
`LogisticMOMParameterEstimator`, `UniformParameterEstimator`,
`TriangularParameterEstimator`. Discrete: `PoissonMLEParameterEstimator`,
`BinomialMOMParameterEstimator` / `BinomialMaxParameterEstimator`,
`NegBinomialMOMParameterEstimator`.

**Goodness-of-fit tests** — `ContinuousCDFGoodnessOfFit` (continuous),
`DiscretePMFGoodnessOfFit` and `PoissonGoodnessOfFit` (discrete). Key members:
`chiSquaredTestStatistic`, `chiSquaredPValue`, `chiSquaredTestResults(...)`.

**Scoring models (`fitting.scoring`)** — the metrics used to rank candidates,
e.g. `AndersonDarlingScoringModel`, `KSScoringModel`, `ChiSquaredScoringModel`,
`CramerVonMisesScoringModel`, `QQCorrelationScoringModel`,
`PPCorrelationScoringModel`, `AkaikeInfoCriterionScoringModel`,
`BayesianInfoCriterionScoringModel`. The modeler uses a default set; you can
supply your own.

---

## 6. Gotchas and best practices

- **Visualize before you fit.** Always look at the histogram, the run-order
  (`ObservationsPlot`), and the autocorrelation (`ACFPlot`) first. Fitting
  assumes independent, identically distributed data — the ACF plot is your check
  for independence.

- **Scoring ranks; GOF tests judge.** The modeler's ranking tells you which
  candidate is *relatively* best. That is not the same as a *good* fit — always
  run a goodness-of-fit test on the chosen distribution and read the p-value.

- **Pass `numEstimatedParameters` to the GOF test.** It adjusts the degrees of
  freedom for the chi-squared test. `ScoringResult.numberOfParameters` gives the
  right value for a fitted distribution.

- **Continuous vs. discrete is a hard fork.** Use `PDFModeler` +
  `ContinuousCDFGoodnessOfFit` for continuous data, and `PMFModeler` +
  `DiscretePMFGoodnessOfFit`/`PoissonGoodnessOfFit` for counts. They are not
  interchangeable.

- **Discrete tests need break points.** Use the `PMFModeler` companion helpers
  (`makeZeroToInfinityBreakPoints`, `equalizedPMFBreakPoints`) rather than
  hand-rolling bins.

- **Pull estimated parameters by name.** From an `EstimationResult`, read a
  parameter with `result.parameters!!.doubleParameter("mean")` (the name depends
  on the distribution).

- **Bootstrap to report uncertainty.** A point estimate alone understates
  uncertainty; use `BootstrapSampler` or `PDFModeler.bootStrapParameterEstimates`
  to attach confidence intervals to your fitted parameters.

---

## 7. See also

- **The distributions themselves:** `ksl.utilities.distributions` (see the
  companion guide) — the objects you fit and then convert with
  `randomVariable(...)`.
- **Generating from the fitted model:** `ksl.utilities.random`.
- **Data summary & plots:** `ksl.utilities.statistic` (`Histogram`,
  `IntegerFrequency`, `BootstrapSampler`) and `ksl.utilities.io.plotting`.
- **Runnable examples:** chapter 2 examples 2.23–2.27 and the appendix B
  examples under `KSLExamples/src/main/kotlin/ksl/examples/book/appendixB`.
- **Theory:** the [KSL Book](https://rossetti.github.io/KSLBook/) input-modeling
  material.
