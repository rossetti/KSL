# Guide: `ksl.modeling`

A task-oriented guide to the KSL's reusable modeling constructs: data-collection
variables and responses, queues, event generation and schedules, and
nonstationary (NHPP) arrivals.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** develops these ideas
  with full worked examples (Chapters 4–7).

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from `KSLExamples/.../book/chapter4` and `.../chapter7`.

> **Scope.** `ksl.modeling` is large. This guide covers the
> **`variable`**, **`queue`**, **`elements`**, and **`nhpp`** subpackages. The
> **`entity`** (process view), **`spatial`** (movement), and **`station`**
> subpackages are covered in separate guides.

---

## 1. What this package is for

`ksl.modeling` provides the **reusable building blocks** you assemble into a
simulation model. Where `ksl.simulation` gives you the engine (`Model`,
`ModelElement`, event scheduling), this package gives you the ready-made model
elements that almost every model needs: things to *collect statistics with*,
*hold waiting entities*, *generate arrivals*, and *drive nonstationary demand*.

Everything here is a `ModelElement` (or works with one), so it plugs directly
into the model-element tree described in the `ksl.simulation` guide.

### The covered subpackages

| Subpackage | Provides | Use it for... |
|---|---|---|
| `modeling.variable` | `Response`, `TWResponse`, `Counter`, `RandomVariable`, and specialized responses | Collecting statistics and injecting randomness into a model. |
| `modeling.queue` | `Queue<T>`, `QObject` | Holding entities in waiting lines, with automatic queue statistics. |
| `modeling.elements` | `EventGenerator`, `Schedule`, `RandomElement`/`RandomList`/`RandomMap` | Generating events/arrivals and time-based schedule changes. |
| `modeling.nhpp` | rate functions + `NHPPEventGenerator` | Generating nonstationary (time-varying-rate) arrivals. |

### How it relates to its neighbors

| Package | Role |
|---|---|
| `ksl.simulation` | The engine these elements plug into (`Model`, `ModelElement`). |
| `ksl.modeling.variable` | The data-collection elements the simulation guide deferred to here. |
| `ksl.utilities.random` | Supplies the `RVariableIfc` sources that `RandomVariable` and generators wrap. |
| `ksl.utilities.statistic` | The statistics machinery the responses build on. |
| `ksl.modeling.entity` / `.spatial` / `.station` | Higher-level constructs (separate guides). |

---

## 2. The mental model

### Everything is a child `ModelElement`

You create these constructs as **children** of your own model element by passing
`this` as the parent — exactly the pattern from the `ksl.simulation` guide. They
participate in the lifecycle automatically: responses reset at warm-up, queues
collect statistics across replications, generators start in `initialize()`.

```kotlin
class MyStation(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {
    private val numBusy = TWResponse(this, name = "NumBusy")     // a child response
    private val waitingQ = Queue<QObject>(this, name = "Queue")  // a child queue
    private val service  = RandomVariable(this, ExponentialRV(0.5, streamNum = 2))
}
```

### Two statistical flavors: observation vs. time-persistent

This is the most important distinction in `modeling.variable`:

| Class | Collects | Use for... |
|---|---|---|
| `Response` | **Observation-based** (tally) statistics on values you assign. | A customer's system time, a measured delay. |
| `TWResponse` | **Time-weighted** (time-persistent) statistics. | Queue length, number-in-system, server utilization. |
| `Counter` | A running **count**. | Number of customers served, number of failures. |

Choosing the wrong flavor gives a statistically incorrect average. "How many are
in the system *on average over time*" is time-weighted (`TWResponse`); "how long
did each customer wait *on average*" is observation-based (`Response`).

### The `CIfc` convention (read-only public views)

Throughout `ksl.modeling`, an element keeps its mutable object **private** and
exposes a **read-only interface** (`ResponseCIfc`, `TWResponseCIfc`,
`CounterCIfc`, `QueueCIfc`, `RandomVariableCIfc`) as a public property. This lets
callers observe results without being able to corrupt internal state.

```kotlin
private val myNS: TWResponse = TWResponse(this, name = "Num in System")
val numInSystem: TWResponseCIfc get() = myNS     // public, read-only
```

Follow this idiom in your own model elements.

### Generation vs. data collection vs. queueing

The other three subpackages each own one job:

- **`elements`** — *make things happen*: `EventGenerator` repeatedly fires an
  action (e.g. customer arrivals); `Schedule` changes state at planned times.
- **`queue`** — *hold things*: `Queue<T>` stores `QObject`s and tracks
  time-in-queue and number-in-queue for you.
- **`nhpp`** — *time-varying generation*: rate functions + `NHPPEventGenerator`
  produce arrivals whose rate changes over time.

---

## 3. Quick start

A minimal single-server queue that uses a response, a counter, a queue, a random
variable, and an event generator.

```kotlin
import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.queue.Queue
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class SimpleServer(parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {

    private val service = RandomVariable(this, ExponentialRV(0.5, streamNum = 2))
    private val numBusy = TWResponse(this, name = "NumBusy")
    private val sysTime = Response(this, name = "System Time")
    private val numServed = Counter(this, name = "Num Served")
    private val waitingQ = Queue<QObject>(this, name = "Queue")

    // generate arrivals with an exponential interarrival time
    private val arrivals = EventGenerator(
        parent = this,
        generateAction = Arrivals(),
        timeUntilFirstRV = ExponentialRV(1.0, streamNum = 1),
        timeBtwEventsRV = ExponentialRV(1.0, streamNum = 1)
    )

    private val endService = this::endOfService

    private inner class Arrivals : GeneratorActionIfc {
        override fun generate(generator: EventGeneratorIfc) {
            val customer = QObject()
            waitingQ.enqueue(customer)
            if (numBusy.value < 1.0) {
                numBusy.increment()
                schedule(endService, service, waitingQ.removeNext())
            }
        }
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        numBusy.decrement()
        if (!waitingQ.isEmpty) {
            numBusy.increment()
            schedule(endService, service, waitingQ.removeNext())
        }
        val departing = event.message!!
        sysTime.value = time - departing.createTime
        numServed.increment()
    }
}
```

---

## 4. How do I...?

### ...collect time-persistent statistics (queue length, utilization)?

Use `TWResponse` and update its `value` (or `increment`/`decrement`). It weights
each value by how long it persisted.

```kotlin
private val numInSystem = TWResponse(this, name = "# in System")
// ...
numInSystem.increment()    // someone arrived
numInSystem.decrement()    // someone left
```

### ...collect observation-based statistics (a delay, a system time)?

Use `Response` and assign its `value` once per observation.

```kotlin
private val systemTime = Response(this, name = "System Time")
// ...
systemTime.value = time - customer.createTime   // one observation per departure
```

### ...count occurrences?

```kotlin
private val numServed = Counter(this, name = "Num Served")
// ...
numServed.increment()           // +1
numServed.increment(5.0)        // +5
```

### ...inject randomness into a model element?

Wrap an `RVariableIfc` in a `RandomVariable` (so it becomes a model element with
stream control and reproducibility). Read values with `.value`.

```kotlin
private val service = RandomVariable(this, ExponentialRV(mean = 0.5, streamNum = 2))
// ...
val t = service.value
```

### ...expose results without exposing mutable state?

Keep the response private and publish the read-only `CIfc`.

```kotlin
private val mySysTime = Response(this, name = "System Time")
val systemTime: ResponseCIfc get() = mySysTime   // callers can read stats, not write
```

### ...derive extra statistics from a response?

The `variable` package has specialized responses that observe another response:

```kotlin
// probability that system time exceeds 4.0
private val stGT4 = IndicatorResponse(
    predicate = { x -> x >= 4.0 },
    observedResponse = mySysTime,
    name = "SysTime >= 4 minutes"
)

// a histogram of an existing response
private val stHist = HistogramResponse(theResponse = mySysTime)
val systemTimeHistogram = stHist.histogram

// frequency tabulation of an integer-valued quantity
private val nqUponArrival = IntegerFrequencyResponse(this, name = "NQ Upon Arrival")
// nqUponArrival.value = waitingQ.numInQ.value.toInt()
```

`AggregateTWResponse` sums several time-weighted responses into one:

```kotlin
private val total = AggregateTWResponse(this, name = "aggregate # in system")
init {
    total.observe(myNumInQ)
    total.observe(myNumBusy)
}
```

### ...model a waiting line?

Create a `Queue<QObject>` (or a queue of your own `QObject` subclass). It records
time-in-queue and number-in-queue automatically.

```kotlin
private val waitingQ = Queue<QObject>(this, name = "PharmacyQ")
// ...
waitingQ.enqueue(QObject())          // add (FIFO by default)
val next: QObject? = waitingQ.removeNext()   // remove the next per the discipline
val peek: QObject? = waitingQ.peekNext()
val n = waitingQ.numInQ.value         // current length (a TWResponseCIfc)
if (!waitingQ.isEmpty) { /* ... */ }
```

Choose a discipline at construction:

```kotlin
import ksl.modeling.queue.Queue

val pq = Queue<QObject>(this, name = "PriorityQ", discipline = Queue.Discipline.RANKED)
```

The `QObject.createTime` is set when the object is created — handy for computing
system time on departure.

### ...generate arrivals at a constant rate?

Use an `EventGenerator` with a `GeneratorActionIfc`. The generator starts itself
at the beginning of each replication and reschedules automatically.

```kotlin
private val ad = ExponentialRV(1.0, streamNum = 1)
private val arrivalGenerator = EventGenerator(
    parent = this,
    generateAction = Arrivals(),
    timeUntilFirstRV = ad,
    timeBtwEventsRV = ad
)

private inner class Arrivals : GeneratorActionIfc {
    override fun generate(generator: EventGeneratorIfc) {
        // ... what happens on each arrival ...
    }
}
```

You can cap generation with `maxNumberOfEvents` and `timeOfTheLastEvent`.

### ...generate nonstationary (time-varying-rate) arrivals?

Build a rate function describing how the rate changes over time, then drive an
`NHPPEventGenerator` with it. `PiecewiseConstantRateFunction` takes interval
durations and the rate in each interval; `PiecewiseLinearRateFunction` ramps
between rates.

```kotlin
import ksl.modeling.nhpp.NHPPEventGenerator
import ksl.modeling.nhpp.PiecewiseConstantRateFunction

// three intervals of length 15, 20, 15 with rates 1.0, 2.0, 1.0
private val rateFunction = PiecewiseConstantRateFunction(
    durations = doubleArrayOf(15.0, 20.0, 15.0),
    rates = doubleArrayOf(1.0, 2.0, 1.0)
)
private val generator = NHPPEventGenerator(this, this::arrivals, rateFunction, streamNum = 1)

private fun arrivals(generator: EventGeneratorIfc) {
    // ... what happens on each (nonstationary) arrival ...
}
```

### ...change model state on a planned schedule?

Use `Schedule` and add scheduled items; register a listener to react to changes
(e.g. open/close a facility, change staffing).

```kotlin
import ksl.modeling.elements.Schedule

val schedule = Schedule(this, startTime = 0.0, length = 480.0)
// add items (start offset, duration) and attach a ScheduleChangeListenerIfc
// to respond when items begin/end
```

### ...randomly select from a set of model elements or values?

The `elements` package provides `RandomList`, `RandomMap`, and `RandomElement`
for random selection that is stream-controlled and reproducible (e.g. routing an
entity to one of several stations).

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**`modeling.variable` — data collection & randomness**

| Type | Role |
|---|---|
| `Response` | Observation-based (tally) statistics on assigned values. |
| `TWResponse` | Time-weighted (time-persistent) statistics. |
| `Counter` | A running count (`increment`/`decrement`). |
| `RandomVariable` | A model element wrapping an `RVariableIfc` (stream-controlled). |
| `IndicatorResponse` | Probability/indicator derived from another response via a predicate. |
| `HistogramResponse` / `IntegerFrequencyResponse` | A histogram / frequency tabulation of a response. |
| `AggregateTWResponse` / `AggregateCounter` | Sum/aggregate several responses or counters. |
| `LevelResponse`, `ResponseSchedule`, `TimeSeriesResponse`, `WeightedResponse` | Specialized collection patterns. |
| `...CIfc` interfaces | Read-only public views (`ResponseCIfc`, `TWResponseCIfc`, `CounterCIfc`, `RandomVariableCIfc`). |

**`modeling.queue`** — `Queue<T : QObject>` (with `Discipline` FIFO/LIFO/RANKED),
`QObject` (the queued item, with `createTime`, `priority`, `attachedObject`),
and the `QueueCIfc` / `QueueListenerIfc` interfaces.

**`modeling.elements`** — `EventGenerator` + `GeneratorActionIfc` (repeating
event generation), `Schedule` + `ScheduleItemData` + `ScheduleChangeListenerIfc`
(planned state changes), `RandomElement` / `RandomList` / `RandomMap` /
`REmpiricalList` (reproducible random selection).

**`modeling.nhpp`** — `PiecewiseConstantRateFunction` /
`PiecewiseLinearRateFunction` (and the `RateFunctionIfc` family),
`NHPPEventGenerator`, `NHPPTimeBtwEventRV` (nonstationary arrivals).

---

## 6. Gotchas and best practices

- **`Response` vs `TWResponse` is a correctness decision, not a style one.** Use
  `TWResponse` for quantities that persist over time (queue length, number busy,
  utilization) and `Response` for per-observation values (waiting time, system
  time). The averaging is different.

- **Pass `this` as the parent.** These constructs must be children of your model
  element to participate in the lifecycle (warm-up reset, across-replication
  statistics). Create them as fields of your `ModelElement`.

- **Expose `CIfc`, keep the implementation private.** Publish
  `ResponseCIfc`/`QueueCIfc`/etc. as read-only properties so callers can read
  results without mutating internal state.

- **Don't start generators yourself.** `EventGenerator` and `NHPPEventGenerator`
  begin firing automatically at the start of each replication; you just define
  the action.

- **Use `QObject.createTime` for system time.** It's stamped at creation, so
  `time - qObject.createTime` on departure gives the time in system without
  bookkeeping.

- **Wrap random sources in `RandomVariable` inside a model.** Don't use a raw
  `RVariableIfc` directly as a model field if you want stream control,
  reproducibility, and reset behavior — wrap it so it becomes a model element.

- **NHPP rate-function arrays must line up.** For
  `PiecewiseConstantRateFunction`, `durations` and `rates` must have matching
  lengths; the rate applies over each successive interval.

- **Change structural parameters only when the model isn't running.** Many
  elements (e.g. number of servers) guard their setters with
  `require(!model.isRunning)`.

---

## 7. See also

- **The engine:** `ksl.simulation` (`Model`, `ModelElement`, scheduling,
  replications) — the foundation these elements plug into.
- **Random sources:** `ksl.utilities.random` for the `RVariableIfc`s that feed
  `RandomVariable` and the generators.
- **Results:** `ksl.utilities.statistic` and `ksl.utilities.io` (reporting,
  databases, plots).
- **Higher-level modeling (separate guides):** `ksl.modeling.entity` (process
  view), `ksl.modeling.spatial` (movement), `ksl.modeling.station`.
- **Runnable examples:** `DriveThroughPharmacyWithQ` and `TandemQueue`
  (chapter 4), the NHPP examples and `StemFairMixerEnhanced` (chapter 7).
- **Theory and walkthroughs:** Chapters 4–7 of the
  [KSL Book](https://rossetti.github.io/KSLBook/).
