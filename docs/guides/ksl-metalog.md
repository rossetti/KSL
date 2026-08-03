# Guide: the [metalog](https://en.wikipedia.org/wiki/Metalog_distribution) distribution family

**Packages:** `ksl.utilities.distributions.metalog`,
`ksl.utilities.random.rvariable.metalog`, and the metalog estimators in
`ksl.utilities.distributions.fitting.estimators`.

**New in R1.5.**

Runnable examples live in `KSLExamples/.../ksl/examples/general/utilities/fitting/`:
`MetalogFittingExample.kt`, `MetalogSPTExample.kt`, `MetalogSimulationExample.kt`
and `MetalogPlottingExample.kt`.

---

## 1. What this package is for

Every named distribution commits you to a shape before you have looked at the
data. A metalog does not. Its shape comes from the data, or from what an expert
is willing to say about quantiles, and it can take shapes no named family offers
— including multi-modal ones, and awkwardly bounded ones.

Reach for a metalog when:

- **no named family fits.** You have tried the usual candidates and none of them
  describes the data.
- **the shape is awkward** — multi-modal, or bounded in a way that a named family
  handles badly.
- **there is no data at all.** An expert gives you a low, a median and a high
  value, and you need a distribution to simulate with. This is the case a
  triangular distribution is usually pressed into, and does badly.

Do **not** reach for one when a named family fits comparably well. §6 says why.

| Package | Role |
|---|---|
| `ksl.utilities.distributions.metalog` | The distributions themselves: `Metalog2P` … `Metalog6P`. |
| `ksl.utilities.random.rvariable.metalog` | The random variables: `Metalog2PRV` … `Metalog6PRV`. |
| `...fitting.estimators` | `MetalogParameterEstimator` — fits a metalog to data. |
| `ksl.utilities.distributions.fitting` | `PDFModeler`, which scores metalog candidates alongside the named families. |

---

## 2. The mental model

A metalog is defined by its **quantile function** rather than its density: you
give it a probability and it returns a value. Two choices define one.

**How many terms.** Two through six. More terms bend the shape more:

| Terms | What it can do |
|---|---|
| 2 | Symmetric, logistic-like |
| 3 | Adds skew |
| 4 | Adds a second bend |
| 5–6 | Multi-modal shapes |

**Where the support ends.** The same coefficients are applied either to the
values or to a transform of them, which is what bounds the distribution:

| `MetalogBoundedness` | Support |
|---|---|
| `Unbounded` | the whole real line |
| `LowerBounded` | a floor, no ceiling — the usual choice for times and costs |
| `UpperBounded` | a ceiling, no floor |
| `Bounded` | both |

Five arities times four boundedness choices is the twenty candidates `PDFModeler`
will fit if you ask it to.

**One thing has no analogue in a named family: not every set of coefficients is a
distribution.** A metalog is only valid when its quantile function increases
everywhere; otherwise it would assign a higher value to a lower probability. Fits
check this for you. If you build coefficients by hand, check them yourself with
`MetalogFeasibilityChecker`.

---

## 3. Quick start

Fit the whole family to data and let scoring choose.

```kotlin
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.random.rvariable.LognormalRV

fun main() {
    val data = LognormalRV(mean = 10.0, variance = 25.0, streamNum = 1).sample(500)

    // Shifting is off because every metalog estimator declines it.
    val results = PDFModeler(data).estimateAndEvaluateScores(
        PDFModeler.metalogEstimators, automaticShifting = false
    )

    val best = results.resultsSortedByScoring.first()
    println(best.distribution)          // e.g. Metalog4P(a1=..., lowerBound=...)
}
```

`PDFModeler.metalogEstimators` is a separate set from `PDFModeler.allEstimators`,
which is why nothing you already fit changes. See §6.

---

## 4. How do I...?

### ...build a distribution from three expert judgements?

This is the case with no data. An expert gives a low, a median and a high value,
and says which probability the outer two represent. Three terms fit three points
exactly.

```kotlin
import ksl.utilities.distributions.metalog.Metalog3P

// "10% chance below 20 minutes, half the time about 40, 10% chance above 90."
val a = Metalog3P.sptCoefficients(
    lowerQuantile = 20.0, median = 40.0, upperQuantile = 90.0, alpha = 0.10
)
val d = Metalog3P(a[0], a[1], a[2])

d.invCDF(0.5)     // 40.0, as elicited
d.invCDF(0.99)    // what the elicitation implies out here
```

**Record which question you asked.** The same three numbers read as quartiles
rather than deciles describe a much wider distribution, because the expert is
then claiming only half the mass lies between them rather than four fifths.

**Add a bound when you know one.** A service time cannot be negative:

```kotlin
val floored = Metalog3P.sptCoefficients(20.0, 40.0, 90.0, alpha = 0.10, lowerBound = 0.0)
```

Not every triplet is representable in three terms — a median very close to one of
the outer quantiles may not be. A bound often rescues it; otherwise elicit more
quantiles and fit more terms.

### ...fit one member rather than the whole family?

```kotlin
import ksl.utilities.distributions.fitting.estimators.MetalogParameterEstimator
import ksl.utilities.distributions.metalog.MetalogBoundedness

val estimator = MetalogParameterEstimator(numTerms = 4, boundedness = MetalogBoundedness.LowerBounded)
val results = PDFModeler(data).estimateAndEvaluateScores(setOf(estimator), automaticShifting = false)
```

Supply `lowerBound` or `upperBound` to the estimator when you know the limit;
leave them null to have it fitted.

### ...compare a metalog against the named families?

```kotlin
val everything = PDFModeler.allEstimators + PDFModeler.metalogEstimators
val results = PDFModeler(data).estimateAndEvaluateScores(everything, automaticShifting = false)
```

Expect the metalogs to take the leading places. That is what more freedom buys,
and it is not by itself an argument for using one — read §6 before acting on the
ranking.

### ...generate variates from a fitted metalog?

Every metalog is a `GetRVariableIfc`, so ask it for a random variable:

```kotlin
val rv = fitted.randomVariable(streamNum = 3)
val sample = rv.sample(1000)
```

Or construct one directly — `Metalog2PRV` through `Metalog6PRV` are parameterized
random variables, so they carry `RVParameters` and work with controls and the
model's parameter machinery like any other.

### ...check that hand-built coefficients are a distribution?

```kotlin
import ksl.utilities.distributions.metalog.MetalogFeasibilityChecker

val checker = MetalogFeasibilityChecker()
checker.isFeasible(coefficients)    // true / false
checker.check(coefficients)         // and why not, if not
```

### ...know whether the moments are trustworthy?

A metalog's moments are computed from its quantile function, and for heavy-tailed
members the higher ones may not converge. Ask before relying on them:

```kotlin
d.momentsAreReliable(order = 2)  // is the variance meaningful?
d.mean()
d.variance()
```

---

## 5. The key types at a glance

| Type | Role |
|---|---|
| `MetalogDistribution` | The abstract base: quantile function, cdf, pdf, moments, support. |
| `Metalog2P` … `Metalog6P` | The concrete distributions, by number of terms. |
| `MetalogBoundedness` | `Unbounded`, `LowerBounded`, `UpperBounded`, `Bounded`. |
| `MetalogFeasibilityChecker` | Whether a set of coefficients is a distribution at all. |
| `MetalogFunctions` | The quantile function and its derivative, for building on. |
| `Metalog2PRV` … `Metalog6PRV` | Parameterized random variables, one per arity. |
| `MetalogParameterEstimator` | Fits one (terms, boundedness) pair to data by least squares. |
| `PDFModeler.metalogEstimators` | All twenty, as an opt-in set. |

---

## 6. Gotchas and best practices

- **Read the fitted distribution, not the label it arrives under.** The family's
  members overlap heavily: a four-term metalog whose fourth coefficient is small
  is nearly a three-term one, and an unbounded metalog whose bound sits far from
  the data agrees with a bounded one everywhere the data lives. Several candidates
  therefore fit any sample about equally well, and which is ranked first is close
  to arbitrary. A report saying "a six-term lower-bounded metalog won" is **not**
  evidence the data came from one. If the number of terms matters to you, fix it
  rather than letting the ranking choose.

- **Coefficients are only comparable between fits of the same boundedness.** Each
  member works in its own space — an unbounded metalog's coefficients are in the
  units of the data, a lower-bounded one's are in the units of the logarithm of
  the data above its bound. Comparing the two directly is meaningless.

- **Winning the scoring is not evidence of being the right model.** Fit twenty
  flexible candidates against nine rigid ones on criteria that reward closeness to
  the sample, and the flexible ones win — including on the parts of the sample
  that are noise. Data generated from a lognormal will rank metalogs above the
  lognormal. Prefer a named family scoring comparably: it carries meaning you can
  defend, has fewer parameters, and extrapolates in a way someone can reason
  about.

- **The estimators are opt-in, deliberately.** They are absent from
  `PDFModeler.allEstimators` because adding twenty candidates to the default set
  would change the recommended distribution for data that existing callers already
  fit. Ask for `metalogEstimators` when you want them.

- **Turn automatic shifting off.** Every metalog estimator declines shifting, so
  pass `automaticShifting = false` and avoid the wasted work.

- **Not every set of coefficients is a distribution.** Fits check feasibility;
  hand-built coefficients do not. Check with `MetalogFeasibilityChecker` before
  sampling from something you assembled yourself, and note that a model's
  parameter-change path will refuse an infeasible set rather than sampling
  nonsense from it.

- **Check the moments before quoting them.** `momentsAreReliable(order)` exists
  because for some members they do not converge.

---

## 7. See also

- **Fitting in general:** [`ksl.utilities.distributions.fitting`](ksl-utilities-distributions-fitting.md)
  — the scoring machinery, goodness-of-fit and the `PDFModeler` workflow.
- **The named families:** [`ksl.utilities.distributions`](ksl-utilities-distributions.md).
- **Generating variates:** [`ksl.utilities.random`](ksl-utilities-random.md).
- **How candidates are scored:** [`ksl.utilities.moda`](ksl-utilities-moda.md) —
  `PDFModeler` ranks fits with an additive MODA model.
- **Theory:** Keelin, T. W. (2016), *The Metalog Distributions*, Decision Analysis 13(4).
