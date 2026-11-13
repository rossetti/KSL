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
  the control flow. It has its own passive `SResource` rather than
  reusing this package's `ResourceWithQ`.
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

**A member may also be seized on its own, or belong to more than one
pool.** However a unit is freed (released directly, released through a
pool, or added by a capacity increase), it is offered to every request
that may want it: those naming the resource and those naming a pool it
belongs to. The request that has waited longest gets it first, and each
queue keeps its own discipline. To offer freed units queue by queue in the
order the queues were registered, as before R1.8, set the resource's
`initialRequestQueueNotificationRule` to `RegistrationOrderNotificationRule`.

### ...renege (give up waiting for a resource)

`seizeOrTimeout` seizes as `seize` does, but gives up when the time runs out:

```kotlin
val r = seizeOrTimeout(doctors, timeout = 120.0)
if (r.timedOut) {
    leftWithoutBeingSeen.increment()      // count reneges yourself
    return@process
}
val a = r.value
delay(examTime)
release(a)
```

It has a form for every `seize` that waits in a queue: a resource (with a queue or its
own), a movable resource, a resource pool and a movable resource pool. Each takes the
`seize` parameters plus `timeout`, right after the amount (or after the resource when
there is none), and `timeoutPriority` and `recordRenegedWait`.

- While it waits, the request is in the queue exactly as a `seize` request, so it is
  served in the same order. A release or capacity increase that serves it wins over the
  timer at the same instant, at the default priorities.
- On a timeout the request leaves the queue and nothing is allocated. There is no
  built-in count of reneges: count them from `timedOut`.
- **Reneged waits in the statistics.** By default a reneged wait is recorded in the
  queue's time in queue, so `NumInQ` and `TimeInQ` agree (Little's law). With
  `recordRenegedWait = false` it is left out of `TimeInQ` while `NumInQ` has counted it,
  so the two no longer agree.
- If a unit promised to the request goes away between its wake and its resume, it
  takes another free unit if there is one, and otherwise waits again for the time left.
- To wait for a resource or something else, whichever comes first, use a seize trigger
  (`resource.seizeTrigger()`, `pool.seizeTrigger()`) in `waitForFirst`; see
  [...wait for whichever comes first](#wait-for-whichever-comes-first).

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

For shifts decided by the model rather than a timetable, call
`resource.goOffShift()` and `resource.goOnShift()` on any resource, or call
`changeCapacity` with a `CapacityChangeNotice`. `goOffShift` drops the
capacity to zero, `goOnShift` restores the replication's `initialCapacity`,
`isOffShift` says which, and every replication starts on shift. A resource
with a capacity schedule takes its capacity only from the schedule, so it
refuses `goOffShift`: use one or the other. A notice with the default, infinite
duration lasts until another change supersedes it, so going back on shift
works whatever is busy, under either rule; under WAIT, going back on shift
before the busy unit was released withdraws the off-shift change.
`pendingCapacityChangeEndTime` says when a pending change ends (`NaN` when
none is pending, infinity when it lasts until superseded). A member of a
pool that comes back on shift is offered to the pool's waiters at once.

### ...give a resource its own behavior

A resource can run a process of its own every replication, such as taking
a break. The usual form is `cyclicBehavior { }`, whose block is one cycle
that the library repeats:

```kotlin
val doctor = ResourceWithQ(this, "Doctor")
init {
    doctor.cyclicBehavior {
        delay(120.0)
        doctor.goOffShift()
        delay(15.0)
        doctor.goOnShift()
    }
    doctor.onBehaviorStopped { if (doctor.isOffShift) doctor.goOnShift() }
}
```

A break that waits for the doctor to finish with the patient, rather than
cutting in, seizes the doctor like a customer and queues with them:

```kotlin
doctor.cyclicBehavior {
    delay(120.0)
    val a = seize(doctor)
    delay(15.0)
    release(a)
}
```

- **Two forms.** `cyclicBehavior { }` repeats its block; `behavior { }`
  runs its block once per replication, for a set-up that is not a cycle,
  such as a delayed start. A resource has at most one behavior, declared
  before the model runs. Anything a process can do is allowed in the block,
  including the waits with a time limit.
- **Every replication.** The resource starts the behavior at time 0, after
  every element has initialized, as a fresh entity of its own, and the end
  of the replication stops it, releasing anything it holds.
- **Stopping and starting.** `stopBehavior()` lets the current cycle finish
  and starts no other; `stopBehavior(BehaviorStop.NOW)` ends it at once,
  releasing what it holds and running its `finally` blocks. The action given
  to `onBehaviorStopped` then puts back what the behavior changed (a stop in
  the middle of a break would otherwise leave the resource off shift).
  `startBehavior()` starts it again, fresh. `isBehaviorActive` and
  `isBehaviorStopRequested` are the checks. The control
  `behaviorActiveAtStart` decides whether each replication starts it, so an
  experiment can compare runs with and without it.
- **The zero-time guard.** A cycle that ends at the time it began, more than
  `maxCyclesPerInstant` (10 000) times in a row, throws, naming the resource:
  a cycle such as `if (busy) delay(10.0)` with `busy` false never waits and
  would otherwise hang the run.
- **One behavior for the whole resource,** however large its capacity. For
  per-unit behavior, use separate resources or a pool.
- **Captured state carries over.** The block is one lambda run by a new
  entity each replication: its local variables start fresh, but anything it
  captures from outside keeps its value between replications. State that
  must reset belongs inside the block.
- **With a capacity schedule.** A behavior runs on a resource that has a
  schedule, but the schedule owns the capacity: the behavior's
  `goOffShift`, or any capacity change not from the schedule, is refused.
  Use a schedule or shifts taken by a behavior, not both.
- `behaviorProcess` is the running behavior's process, so another process
  can interrupt its delays.

### ...fail and repair a resource

Any resource can fail and be repaired, by a call or on a declared schedule:

```kotlin
val machine = ResourceWithQ(this, "Machine")
init {
    machine.interruptRule = InterruptRule.RESUME
    machine.failures(
        timeBetween = ExponentialRV(120.0, 3),
        repairTime = ExponentialRV(10.0, 4),
        basis = ResourceFailureBasis.BUSY_TIME
    )
}
```

or, from any process or event, `machine.fail()` and later `machine.repair()`
(`isFailed` is the check; failing a failed resource, or repairing one that is
not failed, throws).

- **While failed** no unit can be allocated: requests wait in their queues,
  and a pool passes over the member. Capacity is unchanged, and capacity
  changes and shifts still apply underneath; at the repair the resource takes
  the state they give and its free units go to the waiting requests. The
  state is `<name>_Failed`, and it takes precedence over idle, busy and
  inactive.
- **Work in service** follows the interrupt rule, the resource's
  `interruptRule` unless `fail(rule)` names one:
  - `FINISH` (the default): holders complete their work, but the units they
    release are kept from the queue until the repair;
  - `RESUME`: a holder's delay is held, and goes on with the time it had left
    when the units come back;
  - `RESTART`: as `RESUME`, but the delay starts again in full.
  A movement is never held: a holder moving a failed vehicle finishes the
  trip, and its next delay begins held.
- **The bases** of `failures(…)`: `CALENDAR_TIME` counts simulated time;
  `BUSY_TIME` counts time with a unit busy, on shift and not failed, so the
  failure comes at the exact instant that much work has been done; `USES`
  fails at the release that completes the given number of uses. A failure
  that comes due while the resource is failed by hand waits for that repair,
  and a repair by hand during a declared failure ends it early. The failures
  are waited out by an entity of the resource's own, started each
  replication, separate from a behavior.
- **Statistics are added only when asked for.** `failures(…)` or
  `collectFailureStatistics()` adds `<name>:PTimeFailed` and
  `<name>:NumFailures`; without them no response is added and existing
  reports are unchanged.
- **A guided transporter** that is failed is not dispatched, and is offered
  to its pools again at the repair.
- **To have a process told** of the failure instead of held, see
  "...react to a failure or preemption".

### ...let urgent work take a resource (preemption)

`preempt` seizes like `seize`, but when too few units are free it takes them
from holders of lower priority (a larger `priority` number):

```kotlin
private inner class Emergency : Entity() {
    init { priority = 1 }
    val script: KSLProcess = process(isDefaultProcess = true) {
        val a = preempt(doctor)          // or preempt(doctor, rule = InterruptRule.RESTART)
        delay(treatment)
        release(a)
    }
}
```

- **Who is preempted.** Free units are taken first; then units of the
  holders of lowest priority, the most recently allocated first among
  equals, only as many as are still needed. A holder of equal or higher
  priority is never preempted. When the free units and all the lower-priority
  holders' units together are too few, or the resource is failed or inactive,
  nothing is taken: `preempt` waits like `seize` and does not preempt later.
- **The rule** is the resource's `interruptRule` unless the call names one.
  Under `RESUME` and `RESTART` the units are taken now and the holder's delay
  is held. Under `FINISH` nothing is taken in service: the preemptor is
  promised the free units and takes the rest as units are released, ahead of
  everyone waiting in the queue.
- **The units go back.** The holder keeps its allocation. When the preemptor
  releases, the units it took go back to their holders first, newest first,
  and only then to the queue. Preemptions can nest; the units go back along
  the chain. A holder that releases or is terminated while its units are
  taken ends that loan, and the units go to the queue at the preemptor's
  release.
- **Counting.** A unit counts as busy once, for whoever is using it.
  `numTimesPreempted` counts the preemptions in a replication; a preemption
  counts one seize.
- **A waiting preemptor in a `RANKED` queue** is served ahead of waiting
  requests of lower priority, as any request of its priority is.
- A movable resource cannot be preempted.
- **To have a holder told** of a preemption instead of held, see
  "...react to a failure or preemption".

### ...hold a resource across work that does not use it

By default a delay depends on every resource its entity holds, so units of
any of them going missing (a failure or a preemption under `RESUME` or
`RESTART`) hold the delay. When an entity holds a resource across work that
does not use it, name what the work uses:

```kotlin
val bay = seize(loadingBay)
val truck = seize(forklift)
delay(loadTime, uses = listOf(truck))   // a bay failure does not stop the loading
release(truck)
```

Only the named allocations can hold that delay; `uses = emptyList()` means
no resource can (a process interrupt still can). For a pool, pass
`poolAllocation.allocations`.

A held holder keeps its other resources and is not told: its process simply
resumes later. To learn of an interruption afterwards, read the allocation's
records, `timesInterrupted` and `timeInterrupted`. To be told at the moment it
happens, use `delayOrInterrupt` (next entry).

### ...react to a failure or preemption

A plain `delay` is held while units it depends on are missing, and its
process is not told. `delayOrInterrupt` runs as a delay does, but ends at the
moment units go missing and returns what happened, so the process can decide:

```kotlin
val room = seize(rooms)
val doc  = seize(doctors)
when (val r = delayOrInterrupt(treatmentRV, uses = listOf(doc, room))) {
    is DelayResult.Completed -> { release(doc); release(room) }
    is DelayResult.Interrupted -> {
        // wait up to 15 minutes for the doctor to come back, else see another one
        val back = waitForFirst(r.by.restoredTrigger(), timeout = 15.0)
        val d = if (back.timedOut) { release(r.by); seize(otherDoctors) } else r.by
        delayOrInterrupt(r.timeRemaining, uses = listOf(d, room))   // the rest of the work
        release(d); release(room)
    }
}
```

- **Which to use.** `delay` when the work simply waits for the resource to
  come back; `delayOrInterrupt` when the process should choose. The resource's
  `interruptRule` still decides whether units are taken at all: under `FINISH`
  nothing is taken, so neither form is interrupted.
- **The result.** `Completed` has the `duration` and the `timeElapsed`.
  `Interrupted` adds `by` (the allocation whose units went missing first),
  `cause` (`FAILURE` or `PREEMPTION`), `timeDone`, `timeRemaining` and the
  `rule` of the taking (`RESUME` or `RESTART`), which says whether to go on
  with `timeRemaining` or start again with `duration`. The rest of the work is
  not resampled: pass the time you want.
- **The units stay missing.** Reporting changes only what happens to the
  holder: a failed resource is still failed and a lent unit is still lent. The
  process can wait for them, release the allocation (a failed resource keeps
  the unit from the queue until its repair; a lent one ends the loan), or carry
  on with what it holds.
- **Already missing.** If units it depends on are missing when it is called, it
  returns `Interrupted` at once, with nothing done. A process interrupt holds it
  as it holds any delay; units going missing then end it at once.
- **The triggers.** `allocation.interruptedTrigger()` fires when units of that
  allocation go missing, with the cause; `allocation.restoredTrigger()` when all
  have come back. Each fires at once if its condition already holds. They
  compose with any other trigger in `waitForFirst`, and any entity may wait on
  them, so a supervisor can wait for another entity's resource to come back. A
  released allocation's triggers cannot be waited on. When the work itself is a
  wait whose time is the work's length, compose it directly:

  ```kotlin
  val w = waitForFirst(doc.interruptedTrigger(), calledAway.trigger(), timeout = treatment)
  ```

  Work that completes this way is recorded in the animation trace as a wait
  that timed out.
- `allocation.hasMissingUnits` says whether units are missing now. In the
  trace, an interrupted `delayOrInterrupt` shows `DelayEnded` followed by
  `DelayInterrupted`.

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

Requests are served from the head of `requestQ`, so a request waits behind
an earlier one even when its own items are in the channel. To let it go ahead, set
`buffer.receiverRequestSelector = buffer.FirstFillableRequest()`. To serve by
priority, set `buffer.requestQ.initialDiscipline = Queue.Discipline.RANKED`:
requests are ranked by the receiver's own priority, or by a `blockingPriority`
passed to the wait.

`receiveFromChannel`, `receiveNextFromChannel` and `removeAllFromChannel` take
items directly, without waiting in line, which suits removing items that nobody
is going to ask for. They can't take an item a waiting request wants: that throws, and
the message names the request. To take only what's free, ask first:

```kotlin
buffer.removeAllFromChannel(buffer.availableForRemoval { it.isExpired })
```

`canRemoveFromChannel(item)` and `requestWanting(item)` check a single item, and
the second says who is waiting for it. To remove an item a request wants, remove
the request first with `removeAndTerminateChannelRequest`.

With a finite capacity, a process that sends to a full queue blocks until something
removes an item, and senders go in the order of `senderQ`. So a process that
takes from a queue and puts back into it can stop for good if nothing else removes
items, and so can two processes each sending to a queue the other empties. KSL can't tell when
a sender blocks whether anything will ever remove an item, so the run carries on
to its end, and then warns about any sender blocked for a tenth of the replication or
more. To avoid it, use more capacity or put work into a different queue from
the one it came from. `canSendWithoutWaiting` tells a process whether a send would
go ahead now.

A receiver that should not wait forever uses `waitForItemsOrTimeout` (or
`buffer.waitForOrTimeout(timeout, amount)`); see
[...wait with a deadline](#wait-with-a-deadline).

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
To wait for a signal for at most some time, use `waitForOrTimeout(signal, timeout)`;
see [...wait with a deadline](#wait-with-a-deadline). A hold with a deadline is a signal
with a timeout: wait on a `Signal` with `waitForOrTimeout`, and release the entity with
`signal(entity)` in place of `removeAndResume`.

### ...wait with a deadline

Each wait that ends when something else happens has a form with a time limit.
It returns a `WaitResult`: either `Satisfied`, carrying the value, or `TimedOut`.

```kotlin
val reply = receiveMessageOrTimeout(mailbox, timeout = 5.0)    // ksl.modeling.agent
if (reply.timedOut) giveUp() else handle(reply.value)

val parts = waitForItemsOrTimeout(bin, timeout = 10.0, amount = 2)
val got = parts.valueOrNull() ?: emptyList()

val lit = waitForOrTimeout(greenLight, timeout = 30.0)
val answered = suspendForOrTimeout(callBack, timeout = 3.0)
```

| Untimed | With a time limit |
|---|---|
| `receiveMessage`, `receiveMessageOfType` | `receiveMessageOrTimeout`, `receiveMessageOfTypeOrTimeout` |
| `waitFor(signal)` | `waitForOrTimeout(signal, timeout)` |
| `waitForItems`, `waitForAnyItems`, `q.waitFor`, `q.waitForAny` | `waitForItemsOrTimeout`, `waitForAnyItemsOrTimeout`, `q.waitForOrTimeout`, `q.waitForAnyOrTimeout` |
| `suspendFor(suspension)` | `suspendForOrTimeout(suspension, timeout)` |

While it waits, the entity is exactly where the untimed wait would put it: in the
signal's queue, its request in the blocking queue's request queue, its waiter in the
mailbox. So it is ranked, counted and served in the same way. When the time runs out,
the entity leaves; its wait is recorded as the queue records any wait that ends, and
nothing is taken.

- `result.value` throws on a timeout; check `timedOut` first, or use `valueOrNull()`.
  `timeWaited` is the time from the start of the wait to its end.
- A zero timeout still suspends, so events at the current time can still end the wait.
  `Double.POSITIVE_INFINITY` means no limit, and behaves exactly as the untimed wait.
- If what the wait is for has already happened (a message or items already there),
  the wait ends at once and does not suspend.
- The timer runs late in its instant (`TIMEOUT_PRIORITY`), so something that happens at
  the very instant of the deadline wins. Pass an earlier `timeoutPriority` to let the
  deadline win instead.
- After `suspendForOrTimeout` times out, the suspension's holder must not call
  `resume()`: it throws. Check `isSuspended` first.
- `entity.resumeProcess()` refuses an entity in such a wait; end the wait through what
  it waits for. `canResumeProcess` tells you beforehand.

### ...wait for whichever comes first

To wait for the first of several things, get a *trigger* from each thing, and pass the
triggers to `waitForFirst`:

```kotlin
val reply = mailbox.trigger { it.conversationId == convId }
val alarm = alarmSignal.trigger()
val part = bin.itemsTrigger(amount = 1)

val r = waitForFirst(reply, alarm, part, timeout = 60.0)
when (r.trigger) {
    reply -> handle(r.valueOf(reply))          // an AgentMessage
    alarm -> evacuate()
    part -> assemble(r.valueOf(part))          // a List of items
    null -> giveUp()                           // timed out
}
```

The triggers are `mailbox.trigger(predicate)` and `mailbox.triggerOfType<T>()`,
`signal.trigger()`, `queue.itemsTrigger(amount, predicate)` and
`queue.anyItemsTrigger(predicate)`, and `resource.seizeTrigger(amount)` and
`pool.seizeTrigger(amount)` for a resource, a resource pool or a movable resource pool.
A trigger is only a description, so you can keep one as a property and use it in any
number of waits, and by several entities at once.

```kotlin
val bed = beds.seizeTrigger()
val r = waitForFirst(bed, deteriorated.trigger(), timeout = 240.0)
when (r.trigger) {
    bed  -> admit(r.valueOf(bed))     // the allocation; release it as usual
    null -> leaveHospital()
    else -> transferToICU()
}
```

- **The result.** `trigger` is the trigger that ended the wait, or null on a timeout.
  `firedBy(t)` says whether `t` did. `valueOf(t)` is its value, typed by the trigger,
  and throws if `t` did not end the wait. `valueOrNull(t)` returns null instead. Both
  throw for a trigger that was not passed to the wait.
- **The others are withdrawn at that instant.** When one trigger fires, the wait's
  other registrations leave their sources at once, each recorded as its source records
  a wait that ends. So a second message, signal or item at the same instant goes
  elsewhere.
- **Ties.** In time, the first to happen wins. At one instant, the event that runs first
  wins: the higher-priority event, then the earlier-scheduled one; within one event, the
  order of the code. Argument order decides only when several triggers can already fire
  when `waitForFirst` is called, and between two seize triggers whose requests wait in the
  same queue (they enter it in argument order). Seize requests in different queues on one
  resource are served by the resource's notification rule. Choose a winner by the
  priority of the events that cause the triggers, never by argument order.
- **A seize trigger** yields once before the wait starts, at the lowest `seizePriority`
  among the wait's seize triggers, as `seize` yields. Its value is the allocation; a
  losing seize request leaves its queue at the claim, recorded unless
  `recordRenegedWait = false`.
- **Limits.** At least one trigger, each at most once, and at most one signal trigger,
  because an entity can wait in only one queue.
- With more than one trigger the entity reports `isWaitingForFirst` and the suspend type
  `WAIT_FOR_FIRST`. With one, it reports what the untimed wait reports.

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

### ...interrupt a delay

One process can freeze another's delay: a breakdown interrupting the job
in service, for example. The interrupting process is the one that waits
out the interruption; the target resumes the rest of its delay afterwards
(`interruptDelayAndContinue`) or starts it again (`interruptDelayAndRestart`).
The target must be in the named delay when it is interrupted, so a process
that may find it doing something else checks first:

```kotlin
if (machine.script.isInDelay("work")) {
    interruptDelayAndContinue(machine.script, "work", repairTime)
} else {
    delay(repairTime)   // the machine was idle: down for the repair anyway
}
```

Interrupting a process that is not in the named delay is an error that
says what the target was doing.

A delay can be held for several reasons at once: a process interrupt, a
failure, a preemption. It goes on only when the last one ends. If any of them
asked for a restart, it starts again in full; otherwise it goes on with the
time it had left. A process interrupt's own post time (the
`postInterruptDelayTime` of `interruptDelay`) applies only when that interrupt
is the last reason to end.

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

`Resource` exposes `numBusyUnits`, `numActiveUnits`, and capacity
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
  every suspending verb (`delay`, `delayOrInterrupt`, `seize`, `preempt`,
  `release`, `use`,
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
- `Allocation` — the receipt returned by `seize` or `preempt`; pass to
  `release`. Its `timesInterrupted` and `timeInterrupted` record the units it
  lost to failures and preemption.
- `InterruptRule` (`FINISH`, `RESUME`, `RESTART`) — what happens to work in
  service when a resource fails or is preempted.
- `DelayResult` (`Completed`, `Interrupted`), `InterruptCause` (`FAILURE`,
  `PREEMPTION`) — what `delayOrInterrupt` returns, and why units went missing.
- `ResourceFailureBasis` (`CALENDAR_TIME`, `BUSY_TIME`, `USES`) — what the
  time between declared failures counts.

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
- `WaitResult<T>` (`Satisfied`, `TimedOut`) — the result of a wait with a time limit,
  including `seizeOrTimeout`.
- `WaitTrigger<T>`, `WaitForFirstResult` — a thing to wait for, and the result of
  `waitForFirst`.
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
- **`Resource`'s `numBusyUnits` is not the same as `numActiveUnits` or
  capacity.** Capacity is the configured max; busy is allocated units
  at this instant; queue length is pending requests. Utilization in
  the half-width report is derived from these — read carefully when
  reproducing it programmatically.

---

## 7. See also

**Related KSL packages**

- `ksl.modeling.station` — queueing-network view (passive stations);
  has its own passive `SResource` rather than this package's `ResourceWithQ`.
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
| `book/chapter8/` | `Ch8Section8_4_2_2`, `Ch8Section8_4_2_3` | larger case studies and tactical analysis |
| `general/models/conveyors/` | `Ch8ExampleTandemQExamples` | conveyor-driven tandem queue |
| `general/models/wsc2025/` | `ActiveResourceExample*` (BQ, HQ, Signal) | three coordination patterns side by side |
| `general/models/` | `ClinicDesignA`, `ClinicDesignB` | bigger applied models |
| `general/models/conveyors/` | `ConveyorExample1`–`ConveyorExample4`, `BanksEtAlConveyorExample` | conveyor patterns |

**KSL Book**

For background on replications, warmup, output analysis, and variance
reduction, see [the KSL Book](https://rossetti.github.io/KSLBook/) —
the textbook companion to this guide. Chapter 6 onward covers the
process view in depth.
