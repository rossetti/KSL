# Guide: `ksl.utilities.random`

A task-oriented guide to generating pseudo-random numbers and random variates
with the KSL.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
  Use it when you need the exact signature of a method.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** explains the theory of
  random number generation and variate generation. This guide links to it
  instead of re-deriving the algorithms.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are drawn from
`KSLExamples/src/main/kotlin/ksl/examples/book/chapter2`.

---

## 1. What this package is for

`ksl.utilities.random` is the KSL's engine for **randomness**. It produces the
pseudo-random numbers and random variates that drive every Monte Carlo and
discrete-event simulation, and — crucially — it gives you precise control over
the underlying random number streams so that your experiments are
**reproducible** and support variance-reduction techniques like common random
numbers (CRN) and antithetic variates.

It is the "faucet" in the larger picture: it generates the values that
`ksl.utilities.statistic` collects and summarizes.

### How it is organized

The package is split into sub-packages by responsibility. You will spend most of
your time in `rng` and `rvariable`.

| Sub-package | Role | Key inhabitants |
|---|---|---|
| `random.rng` | The pseudo-random number engine: streams of U(0,1) values. | `RNStreamProvider`, `RNStreamIfc` |
| `random.rvariable` | Random variables that transform U(0,1) into variates. | `KSLRandom`, `RVariableIfc`, the `*RV` classes |
| `random.robj` | Random *objects*: sampling/permuting populations and collections. | `DPopulation`, `DEmpiricalList`, `RList` |
| `random.markovchain` | Discrete-state Markov chains. | `DMarkovChain` |
| `random.mcmc` | Markov-chain Monte Carlo. | MCMC samplers |

### How it relates to its neighbors

| Package | Role |
|---|---|
| `ksl.utilities.random` | **Generates** values (this package — the faucet). |
| `ksl.utilities.distributions` | **Evaluates** probability functions (CDF/PDF/inverse-CDF, moments). |
| `ksl.utilities.statistic` | **Summarizes** the values that were generated. |

A distribution is *not* a random variable. A distribution describes how values
are distributed and can have its parameters changed; a random variable
generates values and is immutable. You can always ask a distribution for a
matching random variable with `randomVariable(...)`.

---

## 2. The mental model

Three layers stack on top of each other. Understanding the stack explains nearly
every class in the package.

```
RNStreamProvider   ── makes ──>   RNStream (U(0,1) values)   ── feeds ──>   RVariable (variates)
```

1. **A provider makes streams.** An `RNStreamProvider` is a factory for
   independent random number streams. Two freshly created providers are
   identical, so `provider.rnStream(1)` from each returns the *same* sequence —
   this is what makes runs reproducible across machines.

2. **A stream produces U(0,1) values.** An `RNStreamIfc` yields uniform numbers
   on (0,1) via `randU01()`. Every random variate in the KSL is ultimately
   built from these uniforms.

3. **A random variable transforms uniforms into variates.** An `RVariableIfc`
   (e.g. `NormalRV`, `ExponentialRV`) wraps a stream and converts its uniforms
   into values from the target distribution. You read values with the `value`
   property or grab many at once with `sample(n)`.

### Two ways to pick a stream

You rarely create a provider by hand. Instead:

- **Pass `streamNum`** when constructing a random variable:
  `NormalRV(20.0, 4.0, streamNum = 3)` binds that variable to stream 3 of the
  default provider. **Use distinct stream numbers for sources you want to be
  independent.**
- **Use `KSLRandom`** — a convenience object exposing the default provider:
  `KSLRandom.defaultRNStream()`, `KSLRandom.nextRNStream()`, plus static
  generators and sampling helpers.

### Reproducibility & variance reduction are stream operations

The reason streams are first-class is control. A stream is divided into
**sub-streams**, and supports antithetic and cloned variants:

| Operation | Effect | Used for |
|---|---|---|
| `resetStartStream()` | Rewind to the start of the sequence. | Repeat an identical sequence (CRN). |
| `advanceToNextSubStream()` | Jump to the next independent sub-stream. | Separate replications. |
| `crnInstance()` | A clone with the same state → same sequence. | Common random numbers. |
| `antitheticInstance()` | A stream producing `1-u` for each `u`. | Antithetic variates. |
| `antithetic = true` | Switch a stream to produce `1-u` in place. | Antithetic variates. |

---

## 3. Quick start

Create a random variable, generate values, and sample an array.

```kotlin
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.TriangularRV

fun main() {
    // a Normal(mean = 20.0, variance = 4.0) bound to stream 1
    val n = NormalRV(20.0, 4.0, streamNum = 1)

    // one value at a time
    for (i in 1..5) {
        println(n.value)
    }

    // or a whole sample at once
    val t = TriangularRV(min = 2.0, mode = 5.0, max = 10.0, streamNum = 1)
    val sample: DoubleArray = t.sample(5)
    println(sample.contentToString())
}
```

---

## 4. How do I...?

### ...generate values from a named distribution?

Construct the matching `*RV` class and read `value`, or call `sample(n)`. Pass
`streamNum` for reproducibility.

```kotlin
val e = ExponentialRV(mean = 10.0, streamNum = 1)
val x = e.value             // a single variate
val data = e.sample(1000)   // a DoubleArray of 1000 variates
```

### ...generate a one-off value without creating an object?

`KSLRandom` exposes static generators for every distribution.

```kotlin
import ksl.utilities.random.rvariable.KSLRandom.rNormal
import ksl.utilities.random.rvariable.KSLRandom.rPoisson
import ksl.utilities.random.rvariable.KSLRandom.rUniform

val v = rUniform(10.0, 15.0)   // U(10, 15)
val x = rNormal(5.0, 2.0)      // Normal(mean = 5.0, variance = 2.0)
val n = rPoisson(4.0)          // Poisson(mean = 4.0)
```

### ...get and control a random number stream directly?

```kotlin
import ksl.utilities.random.rvariable.KSLRandom

val s = KSLRandom.defaultRNStream()
repeat(3) { println(s.randU01()) }      // three U(0,1) values

s.advanceToNextSubStream()              // jump to the next sub-stream
s.resetStartStream()                    // rewind to the very beginning
val s2 = KSLRandom.nextRNStream()       // an independent stream
```

You can also obtain streams straight from a provider, which is what makes two
separate runs reproduce each other:

```kotlin
import ksl.utilities.random.rng.RNStreamProvider

val p1 = RNStreamProvider()
val p2 = RNStreamProvider()             // identical to p1
// p1.rnStream(1) and p2.rnStream(1) produce the SAME sequence
```

### ...use common random numbers (CRN)?

Two equivalent idioms. Clone the stream, or reset it after the first use.

```kotlin
// Approach A: clone — both streams produce the same sequence
val s = KSLRandom.defaultRNStream()
val clone = s.crnInstance()

// Approach B: reset — re-run the same sequence on the same stream
s.resetStartStream()
```

### ...use antithetic variates?

```kotlin
// Approach A: an antithetic clone (produces 1-u for each u of s)
val s = KSLRandom.defaultRNStream()
val ans = s.antitheticInstance()

// Approach B: reset the stream and flip the antithetic flag
s.resetStartStream()
s.antithetic = true
```

### ...build an empirical or mixture distribution?

A **discrete empirical** variable needs the values and their CDF:

```kotlin
import ksl.utilities.random.rvariable.DEmpiricalRV

val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
val cdf = doubleArrayOf(1.0 / 6.0, 3.0 / 6.0, 5.0 / 6.0, 1.0)   // must end at 1.0
val d = DEmpiricalRV(values, cdf, streamNum = 1)
println(d.value)
```

A **mixture** picks one of several random variables according to a CDF:

```kotlin
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.MixtureRV

val rvs = listOf(ExponentialRV(1.5), ExponentialRV(1.1))
val cdf = doubleArrayOf(0.7, 1.0)
val he = MixtureRV(rvs, cdf, streamNum = 1)
println(he.value)
```

### ...truncate or shift a distribution?

```kotlin
import ksl.utilities.Interval
import ksl.utilities.distributions.Exponential
import ksl.utilities.random.rvariable.TruncatedRV
import ksl.utilities.random.rvariable.ShiftedRV
import ksl.utilities.random.rvariable.WeibullRV

// truncate an Exponential to the interval [3.0, 6.0]
val cdf = Exponential(mean = 10.0)
val truncated = TruncatedRV(cdf, Interval(0.0, Double.POSITIVE_INFINITY),
    Interval(3.0, 6.0), streamNum = 1)

// shift a Weibull to the right by 5.0
val shifted = ShiftedRV(5.0, WeibullRV(shape = 3.0, scale = 5.0), streamNum = 1)
```

### ...build functions of random variables (convolutions, transforms)?

Random variables support arithmetic operators and math functions, producing new
random variables. The result keeps a well-defined stream.

```kotlin
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.exp

// Erlang as a sum (convolution) of Exponentials
var erlang: RVariableIfc = ExponentialRV(10.0, streamNum = 1)
for (i in 1..4) {
    erlang = erlang + ExponentialRV(10.0)
}

// Lognormal as exp() of a Normal
val y = exp(NormalRV(2.0, 5.0, streamNum = 1))

// Beta built from two Gammas
val y1 = GammaRV(2.0, 1.0, streamNum = 2)
val y2 = GammaRV(5.0, 1.0)
val beta = y1 / (y1 + y2)
```

### ...generate using acceptance/rejection from a custom density?

Supply a proposal distribution, a majorizing constant, and the target PDF.

```kotlin
import ksl.utilities.random.rvariable.AcceptanceRejectionRV
import ksl.utilities.distributions.Uniform

// proposal Uniform(-1,1), majorizing constant c, and target PDF fOfx (a PDFIfc)
val rv = AcceptanceRejectionRV(Uniform(-1.0, 1.0), 1.5, fOfx, streamNum = 1)
repeat(1000) { rv.value }
```

### ...randomly select from, or permute, a collection?

Use `KSLRandom` or the extension functions on lists/arrays.

```kotlin
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.randomlySelect
import ksl.utilities.random.rvariable.permute

val strings = listOf("A", "B", "C", "D")
println(KSLRandom.randomlySelect(strings))   // equal-probability pick
println(strings.randomlySelect())            // same, as an extension

val mutable = mutableListOf("a", "b", "c", "d")
KSLRandom.permute(mutable)                    // shuffle in place
mutable.permute()                             // same, as an extension
```

### ...sample from a finite population of values?

`DPopulation` (in `random.robj`) wraps a `DoubleArray` for sampling and
permuting.

```kotlin
import ksl.utilities.random.robj.DPopulation

val p = DPopulation(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), streamNum = 1)
p.permute()                  // shuffle the population in place
val draw = p.sample(3)       // sample 3 values
println(draw.contentToString())
```

### ...turn a distribution into a random variable?

Distributions in `ksl.utilities.distributions` produce a matching random
variable via `randomVariable(streamNumber)`.

```kotlin
import ksl.utilities.distributions.Binomial

val dist = Binomial(0.8, 10)        // a distribution (mutable parameters)
val brv = dist.randomVariable(1)    // a random variable on stream 1
println(brv.value)
```

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**The engine (`random.rng`)**

| Type | Use it to... |
|---|---|
| `RNStreamProvider` | Create independent, reproducible streams. Two providers are identical. |
| `RNStreamIfc` | Produce U(0,1) values (`randU01`) and control sub-streams, resets, antithetic, CRN. |

**Generating variates (`random.rvariable`)**

| Type | Use it to... |
|---|---|
| `KSLRandom` | The default-provider front door: get streams, static generators (`rNormal`, ...), `randomlySelect`, `permute`. |
| `RVariableIfc` | The common contract for all random variables: `value`, `sample(n)`, `streamNumber`, arithmetic operators. |
| The `*RV` classes | Generate from a specific distribution — see the catalog below. |

**Random-variable catalog (selection).** Continuous: `NormalRV`,
`ExponentialRV`, `UniformRV`, `TriangularRV`, `WeibullRV`, `GammaRV`, `BetaRV`,
`LognormalRV`, `LogLogisticRV`, `LaplaceRV`, `LogisticRV`, `PearsonType5RV`,
`PearsonType6RV`, `StudentTRV`, `ChiSquaredRV`, `JohnsonBRV`, `ConstantRV`.
Discrete: `BernoulliRV`, `BinomialRV`, `GeometricRV`, `NegativeBinomialRV`,
`PoissonRV`, `DUniformRV`, `ShiftedGeometricRV`. Empirical & composite:
`DEmpiricalRV`, `EmpiricalRV`, `PWCEmpiricalRV`, `MixtureRV`, `TruncatedRV`,
`TruncatedNormalRV`, `ShiftedRV`, `AR1NormalRV`. General techniques:
`InverseCDFRV`, `AcceptanceRejectionRV`, `RatioOfUniformsRV`. Multivariate:
`MVNormalRV`, `BivariateNormalRV`, and the copula variates.

**Random objects (`random.robj`)** — `DPopulation` (sample/permute a
`DoubleArray`), `DEmpiricalList` / `DUniformList` / `RList` (random selection
from collections), `RMap`, `BernoulliPicker`.

**Markov chains (`random.markovchain`)** — `DMarkovChain` (generate state
sequences from a transition matrix), `TwoStateMarkovChain`.

**Supporting interfaces** — `RandomIfc`, `SampleIfc` (the `sample` family),
`ParametersIfc`. Implement these when you build your own generators.

---

## 6. Gotchas and best practices

- **Use distinct `streamNum` values for independent sources.** If two random
  variables share a stream, their values are correlated. Give each independent
  input model its own stream number (as the activity-network example does with
  one stream per activity).

- **Don't confuse a distribution with a random variable.** Distributions
  (`ksl.utilities.distributions`) describe probabilities and have *mutable*
  parameters; random variables generate values and are *immutable*. Convert with
  `distribution.randomVariable(streamNumber)`.

- **Reproducibility comes from the streams, not the variables.** To repeat a run
  exactly, control the streams: construct with explicit `streamNum`, and use
  `resetStartStream()` / `crnInstance()`. Fresh `RNStreamProvider`s are identical
  by design, so the same code reproduces on any machine.

- **CDF arrays for empirical/mixture variables must be cumulative and end at
  1.0.** `DEmpiricalRV` and `MixtureRV` take a CDF, not a PMF — pass increasing
  values whose last entry is exactly `1.0`.

- **Functions of random variables preserve stream semantics.** `erlang + ...`,
  `exp(x)`, `y1 / (y1 + y2)` produce new `RVariableIfc` instances with a defined
  `streamNumber`; you don't lose reproducibility by composing.

- **`sample(n)` vs. `value`.** Use `value` inside a loop when each draw feeds
  logic; use `sample(n)` to grab a whole array for bulk analysis.

- **Antithetic and CRN are stream tools, not variable tools.** Apply them to the
  underlying stream (`antitheticInstance`, `antithetic = true`, `crnInstance`),
  and the variates built on that stream inherit the behavior.

---

## 7. See also

- **Runnable examples:** `KSLExamples/src/main/kotlin/ksl/examples/book/chapter2`
  — every snippet above is adapted from a numbered example there.
- **Summarizing generated data:** `ksl.utilities.statistic` (see the companion
  guide) and its `Statistic`, `Histogram`, and `IntegerFrequency` classes.
- **Distribution math & fitting:** `ksl.utilities.distributions` for CDFs/PDFs
  and `randomVariable(...)`; `ksl.utilities.distributions.fitting` for fitting
  distributions to data and goodness-of-fit testing.
- **Theory and worked walkthroughs:** the
  [KSL Book](https://rossetti.github.io/KSLBook/), especially the random number
  and random variate generation chapters.
