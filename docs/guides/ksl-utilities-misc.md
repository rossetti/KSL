# Guide: `ksl.utilities` — supporting utilities

A task-oriented guide to the smaller, cross-cutting utility areas of the KSL:
array/math helpers, intervals and identity, collections, Monte Carlo
integration, root finding, the observer pattern, and applied "misc" models.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** covers the broader
  simulation context.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from `KSLExamples/.../ksl/examples/general/montecarlo`,
`KSLExamples/.../ksl/examples/book/chapter3` (3.13, 3.14), and the in-package
demos.

> **Scope note.** Unlike the dedicated guides for `statistic`, `random`,
> `distributions`, `io`, and `moda`, this guide *batches several small
> subpackages*. Each section is a focused mini-guide; jump to the area you need.

---

## 1. What this collects

These are the workhorse utilities the rest of the KSL is built on, grouped by
area:

| Area | Where | Use it for... |
|---|---|---|
| **Array & numeric helpers** | `ksl.utilities` (top level), `ksl.utilities.math` | Operating on `DoubleArray`/`IntArray`, numeric comparisons, factorials. |
| **Intervals & identity** | `ksl.utilities` | Closed ranges (`Interval`); named/IDed objects (`Identity`). |
| **Value/saver helpers** | `ksl.utilities` | `GetValueIfc`, `DoubleArraySaver`, `OrderedList`. |
| **Collections** | `ksl.utilities.collections` | Bidirectional maps, map JSON/flatten helpers, tables. |
| **Monte Carlo integration** | `ksl.utilities.mcintegration` | Estimating integrals / expectations by simulation. |
| **Root finding** | `ksl.utilities.rootfinding` | Solving `f(x) = 0`; stochastic approximation. |
| **Observer pattern** | `ksl.utilities.observers` | Decoupled change notification (`Observable`, `Emitter`). |
| **Applied models** | `ksl.utilities.misc` | `CashFlow`, `RQInventoryModel`, G/G/c approximation. |
| **Exceptions** | `ksl.utilities.exceptions` | KSL-specific exception types. |

There is no single unifying abstraction here — treat each section independently.

---

## 2. Array and numeric helpers

### `KSLArrays` and array extensions

`KSLArrays` is a large `object` of operations on primitive arrays; many are also
exposed as Kotlin **extension functions** so you can call them fluently.

```kotlin
import ksl.utilities.KSLArrays
import ksl.utilities.statistics            // DoubleArray.statistics()
import ksl.utilities.random.rvariable.NormalRV

val a = NormalRV(2.0, 0.64, streamNum = 1).sample(100)
val b = NormalRV(2.2, 0.36, streamNum = 1).sample(100)

// element-wise operations via the object
val diff = KSLArrays.subtractElements(a, b)
val csv = KSLArrays.toCSVString(diff)

// or via extensions
val stats = diff.statistics()              // a Statistic over the array
println("min=${diff.min()} max=${diff.max()} mean=${stats.average}")
```

`KSLArrays` covers min/max (and their indices), element-wise arithmetic, 2-D
array parsing (`parseTo2DArray`), CSV formatting, column extraction, scaling,
and much more for `DoubleArray`/`IntArray`/`LongArray`.

### `KSLMath`

`KSLMath` provides numerical constants and safe comparisons — essential when
comparing floating-point results.

```kotlin
import ksl.utilities.math.KSLMath

println(KSLMath.machinePrecision)
println(KSLMath.defaultNumericalPrecision)

// compare doubles with tolerance instead of ==
if (KSLMath.equal(0.1 + 0.2, 0.3)) println("equal within precision")

println(KSLMath.factorial(5))                 // 120.0
println(KSLMath.binomialCoefficient(10, 3))   // 120.0
println(KSLMath.logFactorial(100))            // log-scale for large n
```

---

## 3. Intervals, identity, and value helpers

### `Interval`

A closed interval `[lower, upper]` with containment and basic geometry. Widely
used across the KSL (truncation, root-finding bounds, metric domains).

```kotlin
import ksl.utilities.Interval

val i = Interval(42.0, 48.0)
println(i.width)        // 6.0
println(i.midPoint)     // 45.0
println(45.0 in i)      // true  (operator contains)
```

### `Identity` / `IdentityIfc`

Gives an object an auto-assigned unique `id` and a `name`. Many KSL classes
implement `IdentityIfc` (often by delegation: `... by Identity(name)`).

```kotlin
import ksl.utilities.Identity

val a = Identity("MyThing")
println("${a.id} ${a.name}")     // unique id + the supplied name
val b = Identity()               // unnamed -> name defaults to "ID_<id>"
```

### `GetValueIfc` and `DoubleArraySaver`

`GetValueIfc` is the functional interface for "something that yields a `Double`"
(random variables, responses, constants implement it). `DoubleArraySaver` is an
`ObserverIfc<Double>` that accumulates observed values into an array.

```kotlin
import ksl.utilities.DoubleArraySaver

val saver = DoubleArraySaver()
saver.save(3.0)
saver.save(doubleArrayOf(1.0, 2.0))
val data: DoubleArray = saver.savedData()
saver.clearData()
```

---

## 4. Collections — `ksl.utilities.collections`

Helpers that extend the standard Kotlin collections.

```kotlin
import ksl.utilities.collections.KSLMaps
import ksl.utilities.collections.toJson

// build a String -> Double map from parallel arrays
val m = KSLMaps.makeMap(arrayOf("a", "b", "c"), doubleArrayOf(1.0, 2.0, 3.0))

// flatten/unflatten nested maps, and JSON round-trips
val json = m.toJson()
val back = KSLMaps.stringDoubleMapFromJson(json)
```

Also here: `BiMap` (a bidirectional map with `inverse` lookup), `Sets`
(set-operation helpers), and `Table` (a two-key table).

---

## 5. Monte Carlo integration — `ksl.utilities.mcintegration`

Estimate an integral or an expectation by simulation, with automatic
sample-size control to a desired error bound.

### One-dimensional integration

Supply a `FunctionIfc` (an `f(x): Double`) and a sampler random variable over
the integration region.

```kotlin
import ksl.utilities.math.FunctionIfc
import ksl.utilities.mcintegration.MC1DIntegration
import ksl.utilities.random.rvariable.UniformRV
import kotlin.math.sin

// integrate pi * sin(x) over [0, pi]
val f = FunctionIfc { x -> Math.PI * sin(x) }
val mc = MC1DIntegration(f, UniformRV(0.0, Math.PI, streamNum = 3))
mc.runSimulation()
println(mc)                 // estimate + confidence interval
```

`MCMultiVariateIntegration` does the same with a `FunctionMVIfc` and a
multivariate sampler.

### A general Monte Carlo experiment

For any "run a replication, return a number" computation, implement
`MCReplicationIfc` and drive it with `MCExperiment`. You set an error bound and
it determines the sample size.

```kotlin
import ksl.utilities.mcintegration.MCExperiment
import ksl.utilities.mcintegration.MCReplicationIfc
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.RVariableIfc

class NewsVendor(var demand: RVariableIfc) : MCReplicationIfc {
    var orderQty = 30.0
    override fun replication(j: Int): Double {
        val d = demand.value
        return 0.25 * minOf(d, orderQty) + 0.02 * maxOf(0.0, orderQty - d) - 0.15 * orderQty
    }
}

val demand = DEmpiricalRV(
    doubleArrayOf(5.0, 10.0, 40.0, 45.0, 50.0, 55.0, 60.0),
    doubleArrayOf(0.1, 0.3, 0.6, 0.8, 0.9, 0.95, 1.0),
    streamNum = 3
)
val exp = MCExperiment(NewsVendor(demand))
exp.desiredHWErrorBound = 0.01
exp.runSimulation()
println(exp)
```

---

## 6. Root finding — `ksl.utilities.rootfinding`

Find `x` such that `f(x) = 0` on an interval. `BisectionRootFinder` is the
standard deterministic solver.

```kotlin
import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc
import ksl.utilities.rootfinding.BisectionRootFinder

// solve x^2 - 2 = 0 on [0, 2]  -> sqrt(2)
val f = FunctionIfc { x -> x * x - 2.0 }
val finder = BisectionRootFinder(f, Interval(0.0, 2.0))
finder.evaluate()
println("root = ${finder.result}")
```

The package also provides `StochasticApproximationRootFinder` (for noisy
functions, e.g. in simulation optimization) and `GridEnumerator` for scanning a
function over a grid of points.

---

## 7. Observer pattern — `ksl.utilities.observers`

Two complementary mechanisms for decoupled notification, used throughout the KSL
for data collection.

### `Observable` / `ObserverIfc` (classic observer)

```kotlin
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObserverIfc

class Thermometer : Observable<Double>() {
    fun record(temp: Double) = notifyObservers(temp)   // protected; called internally
}

val t = Thermometer()
t.attachObserver(object : ObserverIfc<Double> {
    override fun onChange(newValue: Double) { println("observed $newValue") }
})
t.record(98.6)
println("observers: ${t.countObservers()}")
```

`DoubleArraySaver` (Section 3) is a ready-made `ObserverIfc<Double>` you can
attach to capture a stream of values.

### `Emitter` (lambda-based callbacks)

`Emitter<T>` offers a lighter, connection-based callback model.

```kotlin
import ksl.utilities.observers.Emitter

val emitter = Emitter<Double>()
val connection = emitter.attach { v -> println("got $v") }
emitter.emit(42.0)
emitter.detach(connection)
```

---

## 8. Applied models — `ksl.utilities.misc`

A few self-contained analytical models:

- **`CashFlow`** — net-present-value style cash-flow calculations at a given
  rate.
- **`RQInventoryModel`** — an (r, Q) inventory model.
- **`GGcWhittApproximation`** — Ward Whitt's approximation for steady-state G/G/c
  queue performance (given mean/SCV of interarrival and service times and the
  number of servers).

These are standalone helpers rather than part of a framework; consult their
KDoc/constructors for the exact parameters.

---

## 9. Exceptions — `ksl.utilities.exceptions`

KSL-specific exception types thrown by the library, useful to catch
selectively:

| Exception | Raised when... |
|---|---|
| `KSLEventException` | An event-scheduling error occurs. |
| `KSLTooManyIterationsException` | An iterative process exceeds its iteration limit. |
| `TooManyScansException` | Conditional-action processing scans too many times. |
| `NoSuchStepException` | An iterative process is asked for a step that does not exist. |
| `IllegalStateException` | A KSL-specific illegal-state condition. |

---

## 10. Gotchas and best practices

- **Compare doubles with `KSLMath.equal`, not `==`.** Floating-point results
  from simulation rarely compare exactly; use the tolerance-based helpers.

- **Prefer the array extensions for readability.** `data.statistics()`,
  `data.min()`, `data.max()` read better than the equivalent `KSLArrays` calls;
  both exist.

- **Monte Carlo experiments size themselves.** Set `desiredHWErrorBound` (or use
  the relative-error variant) and let `MCExperiment`/`MC1DIntegration` choose the
  sample size — don't hard-code an iteration count unless you mean to.

- **The sampler must match the integration region.** For `MC1DIntegration`, the
  sampler random variable's support should cover the interval you are
  integrating over (e.g. `UniformRV(0.0, Math.PI)` for `[0, π]`).

- **Root finding needs a sign change.** `BisectionRootFinder` requires the
  interval to bracket a root (the function must change sign across it); check
  your bounds if it fails.

- **`notifyObservers` is protected.** When building an `Observable<T>`, expose
  your own public method that calls `notifyObservers(...)` internally — observers
  attach via `attachObserver`.

- **Identity ids are process-unique and auto-assigned.** Don't rely on specific
  `id` values across runs; they come from a global counter.

---

## 11. See also

- **Statistics on arrays:** `ksl.utilities.statistic` (`Statistic`,
  `Histogram`) — `DoubleArray.statistics()` bridges to it.
- **Random samplers for MC integration:** `ksl.utilities.random`.
- **Writing arrays out:** `ksl.utilities.io` (`writeToFile`, `write`
  extensions).
- **Runnable examples:** `MCExamples.kt` (general/montecarlo), `Ch3Example13.kt`
  and `Ch3Example14.kt` (Monte Carlo experiments and integration),
  `TestKSLArrays.kt` (general/utilities).
- **Theory:** the [KSL Book](https://rossetti.github.io/KSLBook/).
