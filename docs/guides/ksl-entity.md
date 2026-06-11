# Using `ksl.modeling.entity`

A task-oriented usage guide. For each common task, the smallest amount
of code that does it, and the gotchas that matter in practice.
Reference detail (parameter lists, every overload) is on the Dokka API
pages; this guide gets you productive.

## 1. What this package is for

`ksl.modeling.entity` is KSL's **process view** — the modeling style
where each entity's life is written as a *suspending coroutine* and the
simulation runs by stepping these coroutines through their suspensions
(`delay`, `seize`, `release`, `move`, `convey`). It is the long-standing,
textbook-anchored core of KSL's process-style modeling (Rossetti, KSL
Book, chapters 6–8) and is **not experimental** — the API here is
stable.

Reach for it when the natural language of your model is *"a job
arrives, queues for a server, is delayed for some service time, then
leaves."* The script for one entity reads top-to-bottom; the framework
gives you the suspending verbs, the queues, and the statistics.

Reach for a different package when something else fits better:

- `ksl.modeling.station` is the queueing-network view: passive stations
  decide what to do with arriving jobs. Use it when the topology *is*
  the control flow. Internally it uses `ResourceWithQ` from this
  package, so the two views interoperate naturally.
- `ksl.modeling.agent` is the agent-based view: actors react to
  signals, conditions, and messages via a statechart. Bridged to this
  package via `AgentResource` (which is also a `ResourceWithQ`).
- `ksl.modeling.supplychain` is a domain layer specifically for
  multi-echelon inventory networks.

### How it relates to its neighbors

- `ksl.modeling.elements` — `EventGenerator` (the base of
  `EntityGenerator`) and non-homogeneous Poisson arrival sources.
- `ksl.modeling.queue` — every waiting line is a `Queue<QObject>`; this
  package builds `RequestQ`, `HoldQueue`, and the queues inside
  `BlockingQueue` on top.
- `ksl.modeling.variable` — every per-entity or per-resource metric is
  a `Response` / `Counter` / `TWResponse`.
- `ksl.modeling.spatial` — `Location`, `SpatialElement`, and
  `MovableResource` all live there; the suspending `move` and `moveTo`
  verbs in this package operate on those types.
- `ksl.utilities.random` — inter-arrivals, service times, anything
  stochastic.

### One picture

```
ProcessModel
  └── inner class Entity
         └── process { delay / seize / release / move / convey / ... }

   uses ─►  Resource, ResourceWithQ, ResourcePool, ResourcePoolWithQ
            BlockingQueue<T>, HoldQueue, Signal
            Conveyor, MovableResource(WithQ)
```

---

## 2. The mental model

### A `ProcessModel` is the host

Your model subclasses `ProcessModel`. Resources, queues, conveyors, and
random variables are declared as members; entities are *inner classes*.
Everything inherits the parent-child `ModelElement` lifecycle (warm-up,
replication boundaries, statistics) automatically.

### An `Entity` is the unit of work

It carries identity, attributes, an arrival time, and at most one
*active* `KSLProcess` at a time. You can declare *several* process
bodies on the same entity (e.g. a main process plus a recovery
sub-process). One is typically marked `isDefaultProcess = true` so
`EntityGenerator` knows what to activate.

### `process { … }` is a suspending coroutine

Inside the block you write the entity's life-script using the suspending
verbs on `KSLProcessBuilder`. Each suspension yields control back to
the executive; the executive resumes the coroutine at the right
simulated time. From the outside the entity looks like a single object;
from the inside the code reads like a sequence of operations.

### Resources are seizable

`seize(resource)` blocks until the requested capacity is available;
the call returns an `Allocation` — the *receipt* that names which units
you got. `release(allocation)` returns those specific units. The
`use(resource, delayDuration = …)` shorthand is `seize`-`delay`-`release`
in one call; it's also exception-safe.

### Three flavours of coordination structure

- **`BlockingQueue<T>`** — producer-consumer with a bounded buffer and
  backpressure. Producers `send` items (and block if the buffer is
  full); consumers `waitFor` items (and block if it's empty).
- **`HoldQueue`** — entities `hold` themselves into the queue; *you*
  release them programmatically (`removeAndResume` by reference or
  rule).
- **`Signal`** — entities `waitFor(signal)`; raising `signalAll()`
  releases every waiter at once.

### Entities are created on a schedule

- **`EntityGenerator(::Customer, tba, tba)`** — the typical pattern:
  inter-arrival-time-based. The first generic-arg lambda is the
  entity constructor; the two RVs are the time-until-first and the
  inter-arrival distribution. Requires `defaultProcess` to be set on
  the entity.
- **`ProcessActivator(::Customer, initialCountLimit = N)`** — count-based.
  Activates the entity after every N counter increments.
- **`activate(process)`** — direct activation from an event callback or
  another entity's body, when you want explicit control.

---

## 3. Quick start

The Drive-Through Pharmacy (KSL Book Ch. 6, Example 1) — the canonical
first process model. One server, exponential arrivals, exponential
service. Drop into a `main()` and it runs:

```kotlin
import ksl.modeling.entity.EntityGenerator     // (inner class — used inside ProcessModel)
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class Pharmacy(parent: ModelElement, name: String? = null) :
    ProcessModel(parent, name) {

    private val pharmacist = ResourceWithQ(this, "Pharmacist", capacity = 1)
    private val serviceTime = RandomVariable(this, ExponentialRV(0.5, 2))
    private val timeBetweenArrivals = ExponentialRV(1.0, 1)
    private val timeInSystem = Response(this, "TimeInSystem")

    private inner class Customer : Entity() {
        val pharmacyProcess: KSLProcess = process(isDefaultProcess = true) {
            timeStamp = time
            val a = seize(pharmacist)
            delay(serviceTime)
            release(a)
            timeInSystem.value = time - timeStamp
        }
    }

    private val generator =
        EntityGenerator(::Customer, timeBetweenArrivals, timeBetweenArrivals)
}

fun main() {
    val m = Model()
    Pharmacy(m, "Pharmacy")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20_000.0
    m.lengthOfReplicationWarmUp = 5_000.0
    m.simulate()
    m.print()
}
```

What this shows: a `ProcessModel` subclass; one `Entity` inner class
with a `process { … }` body using the three foundational verbs
(`seize` → `delay` → `release`); an `EntityGenerator` that materializes
arrivals from inter-arrival times. Every subsequent recipe is a
variation on this shape.

A subtle but important type distinction visible above:
`timeBetweenArrivals` is a bare `ExponentialRV` (an `RVariableIfc`) so
it can be passed straight to `EntityGenerator`; `serviceTime` is a
`RandomVariable` (a `ModelElement` wrapping an RV) so it has its own
KSL lifecycle and can be inspected and overridden from outside the
class. `delay` accepts either — both implement `GetValueIfc`.

---

## 4. How do I…?

### ...build a minimal process model

The skeleton:

```kotlin
class HelloWorld(parent: ModelElement) : ProcessModel(parent) {
    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            delay(5.0)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(2.0, 1), ExponentialRV(2.0, 1))
}
```

`process(...)` returns a `KSLProcess` and binds it to the entity. With
`isDefaultProcess = true`, the entity's `defaultProcess` slot is set —
which is what `EntityGenerator` activates.

### ...generate entities — three ways

```kotlin
class GeneratorPatterns(parent: ModelElement) : ProcessModel(parent) {
    private inner class Job : Entity() {
        val default: KSLProcess = process(isDefaultProcess = true) { delay(1.0) }
    }

    // (a) inter-arrival-time-based generation
    private val byTime = EntityGenerator(::Job, ExponentialRV(2.0, 1), ExponentialRV(2.0, 1))

    // (b) count-based generation: activate after every N counter increments
    private val byCount = ProcessActivator(::Job, initialCountLimit = 5)

    // (c) explicit activation from an event callback
    override fun initialize() {
        super.initialize()
        schedule(this::startSomething, 1.0)
    }
    private fun startSomething(e: KSLEvent<Nothing>) {
        val j = Job()
        activate(j.default)
    }
}
```

`EntityGenerator` is the workhorse for stochastic arrivals; `ProcessActivator`
covers count-driven scenarios; `activate(...)` is for one-off launches
from inside an event handler or another process body.

### ...use a `Resource` — three patterns

```kotlin
class ResourcePatterns(parent: ModelElement) : ProcessModel(parent) {
    private val r1 = ResourceWithQ(this, "R1", capacity = 1)
    private val r2 = ResourceWithQ(this, "R2", capacity = 2)
    private val st = RandomVariable(this, ExponentialRV(1.0, 2))

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            // (a) explicit seize and release with the allocation receipt
            val a = seize(r1)
            delay(st)
            release(a)

            // (b) the use(...) shorthand — seize, delay, release in one call
            use(r1, delayDuration = st)

            // (c) multi-unit seize
            val twoUnits = seize(r2, amountNeeded = 2)
            delay(st)
            release(twoUnits)
        }
    }
}
```

Pattern (a) is the explicit form: hold the `Allocation` and release
*it*, not the resource. Pattern (b) is the safe shorthand when service
time is the only thing happening between seize and release. Pattern (c)
seizes more than one unit at once.

### ...use a resource that has its own queue

`ResourceWithQ` bundles a `RequestQ` so you don't have to build one by
hand. This is the right default — the queue's statistics flow into the
report automatically.

```kotlin
class WithBundledQueue(parent: ModelElement) : ProcessModel(parent) {
    private val server = ResourceWithQ(this, "Server", capacity = 1)
    private val service = RandomVariable(this, ExponentialRV(0.7, 2))

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            use(server, delayDuration = service)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(1.0, 1), ExponentialRV(1.0, 1))
}
```

Use the bare `Resource` only when several resources must share one
`RequestQ` — the resources' allocation order is then governed by
queue-level rules rather than per-resource queues.

### ...pool resources

A `ResourcePool` lets one queue feed any of several resources. A
`ResourcePoolWithQ` bundles the queue too:

```kotlin
class PoolModel(parent: ModelElement) : ProcessModel(parent) {
    private val r1 = Resource(this, "R1", capacity = 1)
    private val r2 = Resource(this, "R2", capacity = 1)
    private val r3 = Resource(this, "R3", capacity = 1)

    // Pool with its own bundled queue.
    private val pool = ResourcePoolWithQ(this, listOf(r1, r2, r3), name = "Pool")
    private val service = RandomVariable(this, ExponentialRV(0.5, 2))

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = seize(pool)
            delay(service)
            release(a)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(1.0, 1), ExponentialRV(1.0, 1))
}
```

Selection and allocation rules (which resource the pool tries first;
how many units to grant when more than one is requested) are
configurable via `seize`'s `resourceSelectionRule` and
`resourceAllocationRule` parameters — see `ResourceComparators` and
`ResourceRules` for the shipped strategies.

### ...add a shift / capacity schedule

A resource can follow a repeating `CapacitySchedule`. Items run in
order from the schedule's start time; under `CapacityChangeRule.WAIT`,
in-service jobs finish before the resource drops to zero:

```kotlin
class ShiftedModel(parent: ModelElement) : ProcessModel(parent) {
    private val server = ResourceWithQ(this, "Server", capacity = 1)
    private val service = RandomVariable(this, ExponentialRV(0.7, 2))

    init {
        val schedule = CapacitySchedule(
            parent = this,
            startTime = 0.0,
            autoStartOption = true,
            repeatable = true,
        )
        schedule.addItem(capacity = 1, duration = 480.0)  // on shift
        schedule.addItem(capacity = 0, duration = 480.0)  // off shift
        server.useSchedule(schedule, CapacityChangeRule.WAIT)
    }

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            use(server, delayDuration = service)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(1.0, 1), ExponentialRV(1.0, 1))
}
```

The other change rule, `CapacityChangeRule.IGNORE`, lets the resource's
capacity drop immediately; in-service jobs continue but the
resource won't grant new units until the next on-shift block.

### ...run two stations in tandem

Two resources in sequence — the canonical Ch. 7 pattern:

```kotlin
class TandemQ(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {
    private val worker1 = ResourceWithQ(this, "worker1")
    private val worker2 = ResourceWithQ(this, "worker2")
    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    private val wip = TWResponse(this, "NumInSystem")
    private val timeInSystem = Response(this, "TimeInSystem")

    private inner class Customer : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            use(worker1, delayDuration = st1)
            use(worker2, delayDuration = st2)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
    private val gen = EntityGenerator(::Customer, ExponentialRV(2.0, 1), ExponentialRV(2.0, 1))
}
```

The flow is right there in the process body — no separate routing
table needed. Add more `use(...)` calls for longer chains.

### ...coordinate producer and consumer with `BlockingQueue`

A bounded buffer between concurrent producers and consumers:

```kotlin
class ProducerConsumer(parent: ModelElement) : ProcessModel(parent) {
    private val buffer: BlockingQueue<Item> = BlockingQueue(this, capacity = 5, name = "Buffer")

    private inner class Item : Entity()

    private inner class Producer : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                delay(1.0)
                val item = Item()
                send(item, buffer)               // blocks if buffer is full
            }
        }
    }

    private inner class Consumer : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                val items = buffer.waitFor(amount = 1)   // blocks if buffer is empty
                delay(2.0)
            }
        }
    }

    init {
        activate(Producer().script)
        activate(Consumer().script)
    }
}
```

`BlockingQueue<T>` is generic in the queued type, so the buffer is
strongly typed. It exposes three statistics queues — `senderQ`,
`requestQ`, and `channelQ` — which separately track backpressure on
the producer side and waiting on the consumer side.

### ...wait with `HoldQueue` and `Signal`

Two coordination patterns side by side. `hold` parks an entity until
*you* release it (by reference or by rule); `waitFor(signal)` parks
the entity until the signal is raised and releases every waiter at once:

```kotlin
class HoldAndSignal(parent: ModelElement) : ProcessModel(parent) {
    private val parking = HoldQueue(this, "Parking")
    private val greenLight = Signal(this, "GreenLight")

    private inner class Car : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            hold(parking)                         // wait until programmatically released
            waitFor(greenLight)                   // wait until signaled
            delay(1.0)
        }
    }

    fun releaseFirstCar() {
        val first = parking.peekNext()
        if (first != null) parking.removeAndResume(first)
    }

    fun greenLightAll() {
        greenLight.signalAll()
    }
}
```

`HoldQueue` is the right tool for selective release (FIFO by default,
or filter to pick a specific entity). `Signal` is the right tool for
broadcast wakeups (e.g. shift start, batch arrival, light change).

### ...move an entity in space

Movement lives on `KSLProcessBuilder` as a suspending `move` verb;
locations and movable resources come from `ksl.modeling.spatial`:

```kotlin
class MovingResource(parent: ModelElement, dock: LocationIfc, machine: LocationIfc) :
    ProcessModel(parent) {

    private val forklift: MovableResourceWithQ =
        MovableResourceWithQ(this, initLocation = dock, defaultVelocity = ConstantRV(1.0))

    private inner class Job(val from: LocationIfc, val to: LocationIfc) : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = seize(forklift)
            move(forklift, toLoc = from)         // empty travel to pickup
            move(forklift, toLoc = to)           // loaded travel to drop-off
            release(a)
        }
    }
}
```

`MovableResourceWithQ` is the seizable, queueable, movable variant.
`move(movableResource, toLoc)` is one of three overloads — the others
move a `SpatialElement` or move the entity itself between two
locations. See `ksl.modeling.spatial` for the full surface.

### ...convey items on a conveyor

A conveyor is built once with a builder, then used in a process body
through three suspending verbs:

```kotlin
class ConveyedModel(parent: ModelElement) : ProcessModel(parent) {
    private val arrivalArea = "ArrivalArea"
    private val exitArea = "ExitArea"

    private val conveyor: Conveyor = Conveyor.builder(this, "Conveyor")
        .conveyorType(Conveyor.Type.NON_ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(arrivalArea, exitArea, 100)
        .build()

    private val pack = RandomVariable(this, ExponentialRV(2.0, 2))

    private inner class Part : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val req = requestConveyor(conveyor, arrivalArea, numCellsNeeded = 1)
            rideConveyor(req, exitArea)
            delay(pack)
            exitConveyor(req)
        }
    }
    private val gen = EntityGenerator(::Part, ExponentialRV(10.0, 1), ExponentialRV(10.0, 1))
}
```

Three verbs make the conveyor lifecycle explicit:
`requestConveyor` reserves cells at the entry station,
`rideConveyor` advances to the destination,
`exitConveyor` releases the cells.
The combined `convey(conveyor, entry, exit)` verb does all three in one
call when you don't need to interleave other work.

### ...declare multiple processes on one entity

An entity can carry several named processes. The default one is what
`EntityGenerator` activates; other names can be activated by hand or
awaited by other processes:

```kotlin
class NamedProcesses(parent: ModelElement) : ProcessModel(parent) {
    private val server = ResourceWithQ(this, "Server")
    private val st = RandomVariable(this, ExponentialRV(1.0, 2))
    private val repairTime = RandomVariable(this, ExponentialRV(0.5, 3))

    private inner class Job : Entity() {
        val mainProcess: KSLProcess = process(processName = "Main", isDefaultProcess = true) {
            use(server, delayDuration = st)
        }
        val repairSubProcess: KSLProcess = process(processName = "Repair") {
            delay(repairTime)
        }
    }
}
```

Use `blockUntilCompleted(otherProcess)` from inside a process body when
one process should wait for another (e.g. a main flow that waits on a
recovery sub-process). `blockUntilAllCompleted(...)` waits on a list.

### ...read results

Resources, queues, and entity-level responses all surface as the
standard KSL response types. Spot-readings:

```kotlin
fun readResults(server: ResourceWithQ, buffer: BlockingQueue<*>) {
    val avgBusy = server.numBusyUnits.acrossReplicationStatistic.average
    val avgQ = server.waitingQ.timeInQ.acrossReplicationStatistic.average
    val avgWaitInBuffer = buffer.requestQ.timeInQ.acrossReplicationStatistic.average
}
```

`Resource` exposes `numBusyUnits`, `numInUseUnits`, and capacity
information (utilization is busy / capacity in the half-width report).
`ResourceWithQ` adds `waitingQ` (a `QueueCIfc` with all the standard
queue statistics — number in queue, time in queue).
`BlockingQueue<T>` exposes `senderQ` (producers waiting to send),
`requestQ` (consumers waiting to receive), and `channelQ` (items
currently buffered).

---

## 5. The key types at a glance

A compact tour, grouped by what you reach for. Member-level detail is
on the Dokka pages.

**Model & lifecycle**

- `ProcessModel` — the model base class; parent to every `Entity`.
- `ProcessModel.Entity` — inner-class actor; subclass per entity type.
- `KSLProcess` — the runtime handle on a coroutine (state queries:
  `isCreated`, `isSuspended`, `isRunning`, `isCompleted`, …).
- `KSLProcessBuilder` — the receiver for `process { … }`; provides
  every suspending verb (`delay`, `seize`, `release`, `use`,
  `move`/`moveTo`, `requestConveyor`/`rideConveyor`/`exitConveyor`,
  `convey`, `hold`, `waitFor`, `send`/`sendItems`/`waitForItems`,
  `syncWith`, `blockUntilCompleted`/`blockUntilAllCompleted`,
  `waitedForBatch`).

**Entity creation**

- `EntityGenerator(creator, tba, tba)` — stochastic inter-arrival
  generation (inner class of `ProcessModel`).
- `ProcessActivator(creator, initialCountLimit)` — count-based.
- `activate(process, …)` — direct activation.

**Resources**

- `Resource` — bare resource; supply your own `RequestQ`.
- `ResourceWithQ` — resource bundled with a queue (the typical pick).
- `RequestQ` — the queue type that holds waiting `Entity.Request`s.
- `Allocation` — the receipt returned by `seize`; pass to `release`.

**Resource pools**

- `ResourcePool` — set of resources sharing selection rules.
- `ResourcePoolWithQ` — pool bundled with a queue.
- `ResourcePoolAllocation` — receipt for a pooled seize.
- `ResourceComparators`, `ResourceRules` — shipped selection /
  allocation strategies.

**Coordination**

- `BlockingQueue<T>` — bounded buffer; senders block when full,
  receivers when empty.
- `HoldQueue` — entities `hold` themselves; you release by reference
  or rule.
- `Signal` — broadcast wake-up for all waiters.
- `BatchQueue`, `BatchingEntity<T>` — batch-then-resume primitives.

**Capacity scheduling**

- `CapacitySchedule` — repeating capacity items.
- `CapacityChangeRule` (`WAIT`, `IGNORE`) — what happens to in-service
  work when capacity drops.

**Material handling**

- `Conveyor` — `Conveyor.builder(parent, name)…build()`.
- `ConveyorRequestIfc` — the request handle returned by
  `requestConveyor`.
- `Conveyor.Type` — `NON_ACCUMULATING` / `ACCUMULATING`.
- `Segment`, `ConveyorSegments`, `ConveyorQ` — internals exposed when
  you need multi-segment layouts.

**Movement (cross-package)**

- `move`, `moveTo` — suspending; live on `KSLProcessBuilder` but the
  types they operate on (`LocationIfc`, `SpatialElement`,
  `MovableResource`, `MovableResourceWithQ`) live in
  `ksl.modeling.spatial`.

**High-level scaffolding**

- `TaskProcessingSystem` — a higher-level `ProcessModel` subclass for
  building systems of *task processors* that consume typed tasks
  (work / repair / break) and collect per-processor state-time stats.
  Useful when you have many workers of the same kind dispatching off
  a shared task stream.

---

## 6. Gotchas & best practices

- **Suspending verbs only work inside `process { … }`.** Don't try to
  call `delay` or `seize` from an `EventAction` callback — those are
  not coroutine contexts. Use `activate(process)` from the callback
  instead.
- **Hold the `Allocation`, not the resource, to release the right
  units.** `release(allocation)` returns exactly the units that seize
  granted; `release(resource)` releases *everything* the entity is
  currently holding on that resource. The first is almost always what
  you want.
- **`use(resource, delayDuration = …)` is the safe shorthand.** It's
  one call, and it releases the allocation even if the body throws.
  Prefer it over explicit seize/delay/release when the only work
  between them is the delay.
- **`EntityGenerator` requires the entity's `defaultProcess` to be set.**
  Use `process(isDefaultProcess = true) { … }` when declaring the
  process inside the inner class. Without it, the generator throws at
  the first arrival.
- **`process { … }` schedules nothing on its own — it must be
  activated.** `EntityGenerator` and `ProcessActivator` activate
  automatically; manual paths require an explicit `activate(...)`.
- **Inter-arrival type vs. delay type.** `EntityGenerator` expects an
  `RVariableIfc` (the bare RV, e.g. `ExponentialRV(...)`). `delay` and
  `use(delayDuration = …)` accept `GetValueIfc` — which both the bare
  RV and a `RandomVariable` wrapper implement. Wrap in `RandomVariable`
  when you want a `ModelElement` with stream control and externally-
  settable parameters; leave bare when you don't.
- **`BlockingQueue` has *two* statistics surfaces.** Under finite
  capacity, both `senderQ` (producer side) and `requestQ` (consumer
  side) can be non-trivial. Read both when diagnosing backpressure.
- **`Resource`'s `numBusyUnits` is not the same as `numInUseUnits` or
  capacity.** Capacity is the configured max; busy is allocated units
  at this instant; queue length is pending requests. Utilization in
  the half-width report is derived from these — read carefully when
  reproducing it programmatically.

---

## 7. See also

**Related KSL packages**

- `ksl.modeling.station` — queueing-network view (passive stations);
  reuses `ResourceWithQ` from this package.
- `ksl.modeling.agent` — agent-based view (statechart-reactive);
  bridged via `AgentResource` which is also a `ResourceWithQ`.
- `ksl.modeling.supplychain` — multi-echelon inventory domain layer.
- `ksl.modeling.spatial` — where `Location`, `SpatialElement`,
  `MovableResource`, and `MovableResourceWithQ` live; this package's
  `move` verbs operate on those.
- `ksl.modeling.elements` — `EventGenerator` (base of
  `EntityGenerator`), NHPP arrival sources.
- `ksl.modeling.queue` — the `Queue<QObject>` type all waiting lines
  build on.
- `ksl.modeling.variable` — every response type produced by this
  package.

**Other usage guides** *(in `docs/guides/`)*

- `ksl-modeling.md` — the underlying modeling primitives this package
  builds on.
- `ksl-station.md` — the queueing-network view, with cross-references
  back to resource pooling, failures, and capacity schedules.
- `ksl-agent.md` — the agent view, bridged via `AgentResource`.
- `ksl-utilities-random.md` — stream and distribution control.
- `ksl-simulation.md` — the `Model` lifecycle this package runs
  inside.

**Examples** *(under `KSLExamples/.../`)*

The KSL Book chapters are the canonical worked examples:

| Where | Examples | Showcases |
|---|---|---|
| `book/chapter6/` | `Ch6Example1`–`Ch6Example9` | the process view introduced from scratch; resources, queues, statistics |
| `book/chapter7/` | `Ch7Example1`–`Ch7Example4` | tandem queues, resource pools, capacity schedules, conveyors |
| `book/chapter8/` | `Ch8ExampleTandemQExamples`, `Ch8Section8_4_2_2`, `Ch8Section8_4_2_3` | larger case studies and tactical analysis |
| `general/models/` | `ActiveResourceExample*` (BQ, HQ, Signal) | three coordination patterns side by side |
| `general/models/` | `ClinicDesignA`, `ClinicDesignB` | bigger applied models |
| `general/models/conveyors/` | `ConveyorExample1`–`ConveyorExample4`, `BanksEtAlConveyorExample` | conveyor patterns |

**KSL Book**

For background on replications, warmup, output analysis, and variance
reduction, see [the KSL Book](https://rossetti.github.io/KSLBook/) —
the textbook companion to this guide. Chapter 6 onward covers the
process view in depth.
