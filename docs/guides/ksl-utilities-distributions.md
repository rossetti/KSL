# Guide: `ksl.utilities.distributions`

A task-oriented guide to working with probability distributions in the KSL.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
  Use it when you need the exact signature of a method.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** explains the
  underlying probability theory. This guide links to it instead of re-deriving
  formulas.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from
`KSLExamples/src/main/kotlin/ksl/examples/book/chapter2`.

---

## 1. What this package is for

`ksl.utilities.distributions` is the KSL's library of **probability
distributions**. Where the `random` package *generates* values, this package
*describes* them: it evaluates cumulative distribution functions (CDFs),
probability density/mass functions (PDFs/PMFs), inverse CDFs (quantiles), and
distribution moments (mean, variance, skewness, kurtosis).

Reach for it when you need to *reason about* a distribution rather than just
sample from it — computing `P(X <= x)`, finding a percentile, evaluating a
density for plotting, or comparing theoretical to empirical behavior.

### A distribution is not a random variable

This is the single most important distinction in the package:

| | Distribution (`ksl.utilities.distributions`) | Random variable (`ksl.utilities.random.rvariable`) |
|---|---|---|
| Purpose | **Describes** how values are distributed | **Generates** values |
| Parameters | **Mutable** — you can change them | **Immutable** |
| Key members | `cdf`, `pdf`/`pmf`, `invCDF`, `mean()`, `variance()` | `value`, `sample(n)` |

Every distribution can hand you a matching random variable with
`randomVariable(streamNumber)`, which is the bridge from "describe" to
"generate."

### How it relates to its neighbors

| Package | Role |
|---|---|
| `ksl.utilities.random` | **Generates** values (the faucet). |
| `ksl.utilities.distributions` | **Evaluates** probability functions (this package — the math). |
| `ksl.utilities.distributions.fitting` | **Fits** distributions to observed data and tests goodness of fit (see the companion guide). |
| `ksl.utilities.statistic` | **Summarizes** observed values. |

---

## 2. The mental model

Every distribution implements a small stack of interfaces. Once you know the
stack, you know what *any* distribution can do.

```
DistributionIfc
 ├── DistributionFunctionIfc
 │     ├── CDFIfc          -> cdf(x), cdf(x1, x2), complementaryCDF(x)
 │     ├── InverseCDFIfc   -> invCDF(p)            (the quantile / percentile)
 │     ├── MeanIfc         -> mean()
 │     └── VarianceIfc     -> variance()
 ├── ParametersIfc         -> get/set parameters as an array
 └── GetRVariableIfc       -> randomVariable(streamNumber)
```

On top of that:

- **Continuous** distributions (`ContinuousDistributionIfc`) add `pdf(x)` — a
  density.
- **Discrete** distributions (`DiscreteDistributionIfc`) add `pmf(k)` — a
  probability mass.
- Some distributions also implement `LossFunctionDistributionIfc`
  (`firstOrderLossFunction`, `secondOrderLossFunction`), useful in inventory
  modeling.

Three consequences worth internalizing:

1. **The interface is uniform.** `cdf`, `invCDF`, `mean()`, and `variance()`
   work the same way on every distribution, so code written against
   `DistributionIfc` is generic.
2. **Distributions are mutable.** You can change a distribution's parameters in
   place (e.g. `binomial.numTrials = 20`) — handy for parameter studies, and the
   reason they're *not* used directly for generation.
3. **`randomVariable(...)` is the bridge** to the `random` package whenever you
   need to actually sample.

---

## 3. Quick start

Create a distribution, evaluate it, read its moments, then turn it into a random
variable.

```kotlin
import ksl.utilities.distributions.Normal

fun main() {
    val n = Normal(mean = 10.0, variance = 4.0)

    println("mean      = ${n.mean()}")
    println("variance  = ${n.variance()}")
    println("P(X <= 12)= ${n.cdf(12.0)}")          // CDF
    println("density   = ${n.pdf(12.0)}")          // PDF
    println("95th pct  = ${n.invCDF(0.95)}")       // quantile

    // when you need to generate, get the matching random variable
    val rv = n.randomVariable(streamNumber = 1)
    println("a sample  = ${rv.value}")
}
```

---

## 4. How do I...?

### ...evaluate a CDF, PDF/PMF, or quantile?

For a **continuous** distribution use `pdf`; for a **discrete** one use `pmf`.

```kotlin
import ksl.utilities.distributions.Exponential

val e = Exponential(mean = 10.0)
println(e.cdf(5.0))            // P(X <= 5)
println(e.pdf(5.0))            // density at 5
println(e.invCDF(0.5))         // the median
println(e.cdf(3.0, 6.0))       // P(3 < X <= 6)
println(e.complementaryCDF(5)) // P(X > 5)
```

```kotlin
import ksl.utilities.distributions.Binomial

val b = Binomial(0.8, 10)
println(b.pmf(7))              // P(X = 7)
println(b.cdf(7))              // P(X <= 7)
```

### ...get the moments of a distribution?

```kotlin
val g = Gamma(shape = 2.0, scale = 3.0)
println("mean     = ${g.mean()}")
println("variance = ${g.variance()}")
```

### ...do a parameter study by changing parameters in place?

Distributions are mutable. The setters validate their arguments.

```kotlin
import ksl.utilities.distributions.Binomial

val b = Binomial(0.8, 10)
println("mean = ${b.mean()}")        // 8.0

b.probOfSuccess = 0.5                 // change p
b.numTrials = 20                      // change n
println("mean = ${b.mean()}")        // 10.0
```

### ...turn a distribution into a random variable to generate values?

```kotlin
import ksl.utilities.distributions.Binomial

val dist = Binomial(0.8, 10)
val rv = dist.randomVariable(streamNumber = 1)   // bind to stream 1
repeat(5) { println(rv.value.toInt()) }
```

### ...use the standard normal helpers without making an object?

`Normal` exposes companion functions for the standard normal.

```kotlin
import ksl.utilities.distributions.Normal

val z = 1.96
println(Normal.stdNormalCDF(z))        // Phi(z)
println(Normal.stdNormalPDF(z))        // phi(z)
println(Normal.stdNormalInvCDF(0.975)) // ~1.96
```

### ...build a truncated, shifted, or mixture distribution?

A **truncated** distribution restricts a base distribution to an interval. The
constructor takes the CDF-domain limits and the truncation limits:

```kotlin
import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.TruncatedDistribution

val base = Exponential(mean = 10.0)
// truncate to [3.0, 6.0]; the base CDF is supported on [0, +inf)
val truncated = TruncatedDistribution(
    distribution = base,
    cdfLowerLimit = 0.0,
    cdfUpperLimit = Double.POSITIVE_INFINITY,
    lowerLimit = 3.0,
    upperLimit = 6.0
)
println(truncated.cdf(4.0))
```

A **shifted** distribution moves a base distribution to the right by an offset:

```kotlin
import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.ShiftedDistribution

val shifted = ShiftedDistribution(Gamma(shape = 2.0, scale = 3.0), shift = 5.0)
println(shifted.mean())
```

### ...define a discrete empirical distribution?

`DEmpiricalCDF` describes a distribution over a finite set of values via their
CDF (note the parallel to `DEmpiricalRV` in the `random` package).

```kotlin
import ksl.utilities.distributions.DEmpiricalCDF

val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
val cdf = doubleArrayOf(1.0 / 6.0, 3.0 / 6.0, 5.0 / 6.0, 1.0)   // must end at 1.0
val d = DEmpiricalCDF(values, cdf)
println(d.cdf(3.0))
println(d.mean())
```

### ...evaluate inventory loss functions?

Distributions implementing `LossFunctionDistributionIfc` (e.g. `Exponential`,
`Gamma`, `Poisson`, `DEmpiricalCDF`) expose the first- and second-order loss
functions used in inventory analysis.

```kotlin
import ksl.utilities.distributions.Poisson

val p = Poisson(mean = 4.0)
println(p.firstOrderLossFunction(5.0))
println(p.secondOrderLossFunction(5.0))
```

### ...write code that works for any distribution?

Program against the interfaces. Anything typed as `DistributionIfc` (or
`ContinuousDistributionIfc` / `DiscreteDistributionIfc`) gives you `cdf`,
`invCDF`, `mean()`, and `variance()` uniformly.

```kotlin
import ksl.utilities.distributions.ContinuousDistributionIfc

fun report(d: ContinuousDistributionIfc, x: Double) {
    println("mean=${d.mean()} var=${d.variance()} F($x)=${d.cdf(x)} f($x)=${d.pdf(x)}")
}
```

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**Continuous distributions** — `Normal`, `Exponential`, `Uniform`,
`Triangular`, `Gamma`, `Beta`, `GeneralizedBeta`, `Weibull`, `Lognormal`,
`LogLogistic`, `Logistic`, `Laplace`, `PearsonType5`, `PearsonType6`,
`StudentT`, `ChiSquaredDistribution`, `Constant`.

**Discrete distributions** — `Bernoulli`, `Binomial`, `Geometric`,
`NegativeBinomial`, `Poisson`, `DUniform`, `DEmpiricalCDF`.

**Composite / derived** — `TruncatedDistribution`, `TruncatedNormal`,
`ShiftedDistribution`, `ShiftedContinuousDistribution`, `MixtureDistribution`,
`PWCEmpiricalCDF` (piecewise-continuous empirical).

**Multivariate** — `BivariateNormalDistribution`, `CentralMVNDistribution`,
`CentralMVTDistribution`, `MVCDF`.

**Core interfaces** (program against these for generic code):

| Interface | Provides |
|---|---|
| `DistributionIfc` | The full contract: distribution functions + parameters + `randomVariable`. |
| `CDFIfc` / `InverseCDFIfc` | `cdf(x)` and `invCDF(p)`. |
| `ContinuousDistributionIfc` / `DiscreteDistributionIfc` | Adds `pdf(x)` / `pmf(k)`. |
| `MeanIfc` / `VarianceIfc` / `MomentsIfc` | Moments. |
| `LossFunctionDistributionIfc` | First/second-order loss functions. |
| `GetRVariableIfc` | `randomVariable(streamNumber)`. |

---

## 6. Gotchas and best practices

- **Distribution ≠ random variable.** Don't try to "sample a distribution"
  directly. Call `distribution.randomVariable(streamNumber)` and sample the
  result. Distributions are for evaluating probabilities; random variables are
  for generating.

- **`mean()` and `variance()` are functions, not properties.** On distributions
  they are methods (`d.mean()`), reflecting `MeanIfc`/`VarianceIfc`. (Some
  classes also expose mutable `mean`/`variance` *parameter* properties — e.g.
  `Normal` — so read the parameter you intend to change carefully.)

- **`pdf` vs `pmf`.** Continuous distributions have a density (`pdf`); discrete
  ones have a mass function (`pmf`). Calling the wrong one won't type-check, so
  let the compiler guide you.

- **Mutating parameters affects everything downstream.** Because distributions
  are mutable, changing a parameter changes subsequent `cdf`/`pdf` results. If
  you need an independent copy, use `instance()`.

- **CDF arrays for `DEmpiricalCDF` must be cumulative and end at exactly 1.0** —
  the same rule as `DEmpiricalRV`.

- **`invCDF(p)` expects `p` in (0, 1).** It returns the quantile (percentile)
  for that probability.

- **Truncated vs. shifted constructors differ from their RV counterparts.**
  `TruncatedDistribution` takes explicit CDF-domain and truncation limits (not
  `Interval`s), so cross-check the signature when porting from `TruncatedRV`.

---

## 7. See also

- **Generating values:** `ksl.utilities.random` (see the companion guide) — use
  `distribution.randomVariable(streamNumber)` to cross over.
- **Fitting distributions to data:** `ksl.utilities.distributions.fitting` (see
  the companion guide) for parameter estimation and goodness-of-fit testing.
- **Summarizing data:** `ksl.utilities.statistic`.
- **Runnable examples:** `KSLExamples/src/main/kotlin/ksl/examples/book/chapter2`
  (e.g. the `Binomial` distribution example).
- **Theory:** the [KSL Book](https://rossetti.github.io/KSLBook/).
