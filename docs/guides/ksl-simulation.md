# Guide: `ksl.simulation`

A task-oriented guide to the heart of the KSL: building and running
discrete-event simulation models with `Model`, `ModelElement`, and the event
`Executive`.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** — especially
  Chapters 4 and 5 — develops these ideas with full worked examples and the
  underlying theory.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from
`KSLExamples/src/main/kotlin/ksl/examples/book/chapter4` and `.../chapter5`.

---

## 1. What this package is for

`ksl.simulation` is the **discrete-event simulation (DES) engine** of the KSL.
It provides the framework into which you place your model logic and the
machinery that advances simulated time, processes events, runs replications, and
collects results.

Three classes carry the weight:

- **`Model`** — the container for an entire simulation. You create one, attach
  your model logic to it, set the run parameters, and call `simulate()`.
- **`ModelElement`** — the base class for every component of a model. Your
  domain logic lives in subclasses of `ModelElement`.
- **`Executive`** — the event calendar that drives time forward by processing
  scheduled events in time order. You rarely touch it directly; you schedule
  events and it does the rest.

### How it relates to its neighbors

| Package | Role |
|---|---|
| `ksl.simulation` | The DES engine: `Model`, `ModelElement`, `Executive`, events, replications (this package). |
| `ksl.modeling.variable` | The model elements you collect data with: `Response`, `TWResponse`, `Counter`, `RandomVariable`. |
| `ksl.modeling` (process, queue, resource, ...) | Higher-level modeling constructs built on `ModelElement`. |
| `ksl.utilities.random` | The random variates that drive stochastic behavior. |
| `ksl.utilities.statistic` / `ksl.utilities.io` | Summarize and report the results. |

> **Boundary note.** The classes you use to *collect statistics* — `Response`,
> `TWResponse`, `Counter`, `RandomVariable` — are themselves `ModelElement`s, but
> they live in `ksl.modeling.variable`, not here. This guide shows how they plug
> into the `ModelElement` hierarchy; see that package for their details.

---

## 2. The mental model

### `Model` is the root of a tree of `ModelElement`s

Every `ModelElement` has exactly one **parent** and may have many **children**.
The `Model` itself is the root — and because `Model` *extends* `ModelElement`,
the model is just the top of the tree. You build your model by constructing
model elements and giving each a parent:

```
Model  (root; also holds the run parameters)
 ├── DriveThroughPharmacy        (your ModelElement)
 │    ├── RandomVariable "ArrivalRV"
 │    ├── RandomVariable "ServiceRV"
 │    ├── TWResponse "PharmacyQ"
 │    └── Counter "Num Served"
 └── ... other top-level elements
```

The parent link is established **through the constructor**: a `ModelElement`
takes `parent: ModelElement` and registers itself with that parent. The first
argument you pass is what wires your element into the tree. Passing the `Model`
(or any element whose `model` is that `Model`) as the parent attaches your
element to the simulation.

### `Model` is also the experiment

`Model` implements `ExperimentIfc`, so the same object that roots the tree also
carries the run controls: `numberOfReplications`, `lengthOfReplication`,
`lengthOfReplicationWarmUp`, and `experimentName`. Calling `simulate()` runs the
configured experiment.

### The event-scheduling worldview

Inside a model element you make things happen over time by **scheduling
events**. An event couples a future time with an **action** (an `EventAction`).
When the `Executive` reaches that time, it invokes the action; the action
typically updates state, records statistics, and schedules further events. Time
advances event-to-event — nothing happens "between" events.

```
schedule(action, t)  ─▶  Executive holds it on the calendar
                          ─▶ at simulated time t, calls action.action(event)
                              ─▶ action updates state + schedules the next event
```

### The lifecycle: hooks the framework calls for you

You do **not** write a main loop. The framework calls a sequence of `protected
open` methods on every model element at the right moments. Override the ones you
need:

| Hook | Called... | Typical use |
|---|---|---|
| `beforeExperiment()` | Once, before all replications. | One-time setup. |
| `initialize()` | At the start of **each** replication. | Set initial state; schedule the first events. |
| `beforeReplication()` | Before each replication (before `initialize`). | Per-rep setup. |
| `warmUp()` | At the warm-up time, each replication. | Statistics auto-reset; hook for custom action. |
| `timedUpdate()` | At regular timed-update intervals. | Periodic sampling. |
| `replicationEnded()` / `afterReplication()` | At the end of each replication. | Per-rep wrap-up. |
| `afterExperiment()` | Once, after all replications. | Final wrap-up. |

`initialize()` is the one you will override most: it's where each replication
starts the chain of events.

---

## 3. Quick start

A complete model has two parts: a `ModelElement` subclass with your logic, and a
driver that builds the `Model`, attaches the element, and runs it.

```kotlin
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

// 1) the model logic, as a ModelElement subclass
class SchedulingExample(parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {

    private val actionOne = EventActionOne()
    private val actionTwo = EventActionTwo()

    override fun initialize() {
        // schedule the first events relative to current time (0.0)
        schedule(actionOne, 10.0)
        schedule(actionTwo, 20.0)
    }

    private inner class EventActionOne : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            println("EventActionOne at time : $time")
        }
    }

    private inner class EventActionTwo : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            println("EventActionTwo at time : $time")
            schedule(actionOne, 15.0)   // schedule more events from within an action
            schedule(actionTwo, 20.0)
        }
    }
}

// 2) the driver
fun main() {
    val m = Model("Scheduling Example")
    SchedulingExample(m)            // attach to the model (the root element)
    m.lengthOfReplication = 100.0
    m.simulate()
}
```

---

## 4. How do I...?

### ...create my own model element?

Subclass `ModelElement`, take `parent: ModelElement` as the first constructor
argument, and pass it up to the superclass. Create child elements in the
constructor/`init`, passing `this` as their parent.

```kotlin
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class MyComponent(parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {

    // child model elements — note `parent = this`
    private val serviceRV = RandomVariable(this, ExponentialRV(mean = 0.5, streamNum = 2))
    private val numInQ = TWResponse(this, name = "Queue")          // time-weighted response
    private val numServed = Counter(this, name = "Num Served")     // a count
    // ...
}
```

### ...make events happen?

Define an `EventAction`, then `schedule` it. The time argument can be a literal
`Double` or anything `GetValueIfc` (e.g. a `RandomVariable`, so you can schedule
"after a random service time").

```kotlin
override fun initialize() {
    schedule(arrivalAction, myArrivalRV)   // schedule using a random time
}

private inner class ArrivalEventAction : EventAction<Nothing>() {
    override fun action(event: KSLEvent<Nothing>) {
        // ... update state ...
        schedule(arrivalAction, myArrivalRV)   // schedule the next arrival
    }
}
```

The current simulated time is always available as the `time` property inside a
model element.

### ...collect statistics?

Use the response/counter model elements from `ksl.modeling.variable` as children
and update their `value` (or `increment`/`decrement`). The framework collects
across-replication statistics automatically.

```kotlin
private val myNS = TWResponse(this, name = "# in System")   // time-persistent average
private val myCount = Counter(this, name = "Num Served")    // a tally

// inside event actions:
myNS.increment()          // a customer arrived
myNS.decrement()          // a customer left
myCount.increment()       // count a completion
```

- **`Response`** collects tally (observation-based) statistics on values you set.
- **`TWResponse`** collects time-weighted (time-persistent) statistics — use it
  for quantities like queue length or number-in-system.
- **`Counter`** accumulates counts.

### ...run multiple replications and get summary statistics?

Set the run parameters on the `Model`, `simulate()`, then use the
`simulationReporter`.

```kotlin
val m = Model("UpDownComponent")
val tv = UpDownComponent(m)
m.numberOfReplications = 5
m.lengthOfReplication = 5000.0
m.simulate()

m.simulationReporter.printAcrossReplicationSummaryStatistics()
```

There is also a one-call form:

```kotlin
m.simulate(numReps = 50, runLength = 20.0, warmUp = 5.0, expName = "Base Case")
```

### ...handle a warm-up period (delete initialization bias)?

Set `lengthOfReplicationWarmUp`. At that time, the framework resets every
model element's statistics automatically, so only steady-state data is kept.

```kotlin
m.numberOfReplications = 30
m.lengthOfReplication = 20000.0
m.lengthOfReplicationWarmUp = 5000.0   // discard the first 5000 time units
m.simulate()
```

### ...print a full report of the results?

```kotlin
m.print()   // prints across-replication statistics (and histograms/frequencies)
```

Or get statistics programmatically:

```kotlin
val stats = m.simulationReporter.acrossReplicationStatisticsList()  // List<StatisticIfc>
val r = m.response("# in System")     // look up a response by name
val c = m.counter("Num Served")       // look up a counter by name
```

### ...parameterize and reconfigure a model between runs?

Expose mutable properties on your model element and change them, then simulate
again under a new experiment name.

```kotlin
val m = Model("Pharmacy")
val pharmacy = DriveThroughPharmacy(m, numServers = 1)

m.numberOfReplications = 30
m.lengthOfReplication = 20000.0
m.experimentName = "One Server"
m.simulate()

pharmacy.numPharmacists = 2          // reconfigure
m.experimentName = "Two Servers"
m.simulate()
```

### ...react to replication boundaries?

Override the lifecycle hooks (call `super` when overriding `initialize`).

```kotlin
override fun initialize() {
    super.initialize()
    myTimeLastUp = 0.0
    schedule(downChangeAction, myUpTime.value)
}

override fun replicationEnded() {
    // record an end-of-replication observation, if needed
}
```

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

| Type | Role |
|---|---|
| `Model` | The simulation container and root model element; also the experiment (`ExperimentIfc`). Holds `numberOfReplications`, `lengthOfReplication`, `lengthOfReplicationWarmUp`, `simulate()`, `simulationReporter`, `print()`. |
| `ModelElement` | Base class for all model components. Provides `schedule(...)`, the `time` property, the `EventAction` inner class, the lifecycle hooks, and the parent/child tree. |
| `ModelElement.EventAction<T>` / `EventActionIfc<T>` | The action invoked when an event occurs (`action(event)`). |
| `KSLEvent<T>` | A scheduled event: its `time`, `priority`, optional `message`, `name`. |
| `Executive` | The event calendar engine that advances time and dispatches events. |
| `Experiment` / `ExperimentIfc` | The run-control contract (replications, length, warm-up) that `Model` implements. |
| `SimulationReporter` | Across-replication reporting (`printAcrossReplicationSummaryStatistics`, CSV, lists). |
| `ConditionalAction` / `ConditionalActionProcessor` | Actions triggered by conditions rather than scheduled times. |
| `StatisticalBatchingElement` | Batch-means statistics for a single long run. |

**Statistics-collecting elements (in `ksl.modeling.variable`)** — `Response`,
`TWResponse`, `Counter`, `RandomVariable`, `AggregateTWResponse`. These are the
children you most often add to your model elements.

---

## 6. Gotchas and best practices

- **The first constructor argument wires the tree.** Always pass the correct
  `parent` to `ModelElement(parent, name)`, and pass `this` when creating child
  elements. Forgetting this detaches the element from the model.

- **Put first-event scheduling in `initialize()`, not the constructor.**
  `initialize()` runs at the start of *every* replication; the constructor runs
  once. Initial events and initial state belong in `initialize()` so each
  replication starts cleanly.

- **Call `super.initialize()` when you override it** (and similarly for other
  hooks) so the framework's own initialization still runs.

- **Don't write a time loop.** You never advance time yourself. You schedule
  events; the `Executive` advances time and calls your actions. State changes
  happen only inside event actions.

- **Use `TWResponse` for time-persistent quantities, `Response` for
  observations.** Queue length and number-in-system are time-weighted; a
  customer's system time is an observation. Choosing the wrong one gives a
  statistically wrong average.

- **Set a warm-up for steady-state studies.** Without
  `lengthOfReplicationWarmUp`, initialization bias contaminates your estimates.
  The framework resets statistics at the warm-up point automatically.

- **Create model elements before `simulate()`, not during a run.** The element
  tree is built up front; construct your elements, set parameters, then run.

- **Reuse one `Model` across scenarios.** Change parameters and
  `experimentName`, then call `simulate()` again — you don't need a new `Model`
  per scenario.

---

## 7. See also

- **Statistics-collecting elements:** `ksl.modeling.variable` (`Response`,
  `TWResponse`, `Counter`, `RandomVariable`).
- **Higher-level modeling:** the process view, queues, resources, and conveyors
  in `ksl.modeling.*`.
- **Randomness:** `ksl.utilities.random` for the variates that drive your model.
- **Results:** `ksl.utilities.statistic` and `ksl.utilities.io` (the
  `SimulationReporter`, databases, and plots).
- **Runnable examples:** `KSLExamples/.../book/chapter4` (event/activity view —
  `SchedulingEventExamples`, `UpDownComponent`, `DriveThroughPharmacy`,
  `TandemQueue`) and `.../chapter5` (`PalletWorkCenter`, and more).
- **Theory and full walkthroughs:** Chapters 4–5 of the
  [KSL Book](https://rossetti.github.io/KSLBook/).
