# Using `ksl.modeling.station`

A task-oriented usage guide. For each common task, the smallest amount
of code that does it, and the gotchas that matter in practice.
Reference detail (parameter lists, every overload) is on the Dokka API
pages; this guide gets you productive.

> **Status: experimental.** `ksl.modeling.station` is released as
> experimental. Its public API may change in future releases without
> notice. Pin your KSL version if you build models against it for
> production use.

## 1. What this package is for

`ksl.modeling.station` is the **queueing-network modeling layer** in
KSL. You assemble a network out of named station archetypes — sources,
servers, routers, batchers, gates, fork/join pairs, seize/release
pairs — and the package runs the resulting event-driven simulation
under a single `Model`.

Reach for it when the natural language of your model is *"a job
arrives, queues at a station, is served by a resource, then routes to
the next station."* Reach for a different package when something else
fits better:

- `ksl.modeling.entity` is the **process view** — entities author
  their own behavior as suspending coroutines. Use it when each job's
  logic is best written as a script (`hold`, `seize`, `delay`,
  `release`). When the logic is best described as a topology of
  stations the job *flows through*, use this package instead. The two
  views interoperate via the `ProcessStation` bridge (§4).
- `ksl.modeling.supplychain` is a specialized domain layer on top of
  the simulation substrate. Use this package, not that one, when your
  network has servers and routers rather than inventory and lead
  times.

### How it relates to its neighbors

- `ksl.utilities.random` and `ksl.utilities.distributions` supply
  inter-arrival times, service times, time-to-failure, etc.
- `ksl.modeling.variable` provides the `Response` / `Counter` /
  `TWResponse` types every station metric is built on.
- `ksl.modeling.queue` is what waiting lines are built from; every
  station's queue is a `Queue<QObject>`.
- `ksl.modeling.entity` interoperates via `ProcessStation` —
  a station whose "activity" is a `KSLProcess` coroutine.
- `ksl.utilities.io.report` is available if you want to build a
  structured report from a network's responses.

### Three authoring paths, one runtime

```
Kotlin DSL    queueingNetwork { … }   ─┐
TOML / JSON   QueueingNetworkToml      ├─►  QueueingNetworkSpec  ──►  QueueingNetworkModelBuilder.build()  ──►  Model + StationNetwork
programmatic  StationNetwork(parent)  ─┘                                                                                  (simulate as usual)
```

`QueueingNetworkSpec` is the serializable canonical description; the
DSL and TOML/JSON loaders all produce it. You can also build the
network programmatically against `StationNetwork` directly. All three
paths produce a `StationNetwork` that simulates identically given
identical inputs.

---

## 2. The mental model

### A network is a directed graph of receivers

Stations point at the next *receiver* — another station, a router, or
a sink. Multi-source, multi-sink, branching, and cycles are all legal.
The framework doesn't impose a tree; the topology you author *is* the
control flow.

### Jobs are `QObject`s

Each one carries an arrival time, an optional priority, an optional
*type id* (used by `routeByType`), and an optional *value object* the
class system can read at a station. Each `QObject` belongs to one
class (or none — multi-class is opt-in).

### Stations pick the next receiver themselves

A `SingleQStation` finishes service and sends the `QObject` to its
`nextReceiver`. To split the outgoing flow, attach a router — the
station forwards to the router, the router decides. The router pattern
keeps stations decoupled from the topology.

### Resources are first-class but optional

A `SingleQStation` has its own built-in `capacity` (the number of
parallel servers). When you need a resource that is *shared* across
several stations, use `SResource` (or `SResourcePool`) with
`SeizeStation` / `ReleaseStation`. That's the hold-across-stations
pattern: the job carries `SRAllocation`s until released.

### Class-aware everything

Once you register a `QObjectClass`, the network collects per-class
system-time and throughput automatically (`classSystemTime`,
`classNumCompleted`), `routeByType` can dispatch on the class's type
id, and a station can use the class's value object as its service
time. You opt in by attaching classes; the rest of the model keeps
working when you don't.

### The three authoring paths are interchangeable

Same runtime; same observable behavior. Use the DSL for hand-authored
models, TOML/JSON for data-sourced ones, the programmatic API when
you want explicit construction-time control or to embed a network
inside another `ModelElement`.

---

## 3. Quick start

The Drive-Through Pharmacy from chapter 4 of the KSL book, expressed
with the DSL — a source, one server, a sink. Drop into a `main()` and
it runs:

```kotlin
import ksl.modeling.station.queueingNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    val model = Model("DriveThroughPharmacy")

    val net = model.queueingNetwork("Pharmacy") {
        val exit = sink("Exit")
        val pharmacist = station(
            "Pharmacist",
            activityTime = ExponentialRV(0.5, streamNum = 2),
            capacity = 1,
            nextReceiver = exit,
        )
        val arrivals = source("Arrivals", ExponentialRV(1.0, streamNum = 1))
        arrivals routeTo pharmacist
    }

    model.numberOfReplications = 30
    model.lengthOfReplication = 20_000.0
    model.lengthOfReplicationWarmUp = 5_000.0
    model.simulate()

    println("Number in system  = ${net.numInSystem.acrossReplicationStatistic.average}")
    println("System time       = ${net.systemTime.acrossReplicationStatistic.average}")
    println("Throughput        = ${net.numCompleted.acrossReplicationStatistic.average}")
}
```

`net` is a `StationNetworkCIfc` — the read-only network handle. The
full half-width summary is available via
`model.simulationReporter.printHalfWidthSummaryReport()`.

---

## 4. How do I…?

### ...author a network — three ways

Most users will start with the DSL. The other two paths are when (a)
you want to embed a network inside an existing `ModelElement`, or (b)
the topology comes from a file or generator.

**DSL.** Receiver-style declarative wiring:

```kotlin
model.queueingNetwork("Pharmacy") {
    val exit = sink("Exit")
    val pharmacist = station("Pharmacist", ExponentialRV(0.5, 2), capacity = 1, nextReceiver = exit)
    val arrivals = source("Arrivals", ExponentialRV(1.0, 1))
    arrivals routeTo pharmacist
}
```

**Programmatic.** Construct a `StationNetwork` directly under any
`ModelElement` parent — useful when the network is one piece of a
larger model:

```kotlin
val net = StationNetwork(parent, "Net")
val exit: SinkStation = net.sink("Exit")
val server: SingleQStation = net.singleQStation(
    "Server",
    activityTime = ExponentialRV(0.5, streamNum = 2),
    capacity = 1,
    nextReceiver = exit,
)
net.source("Arrivals", ExponentialRV(1.0, streamNum = 1), firstReceiver = server)
```

**From a data spec (TOML / JSON).** Author a `QueueingNetworkSpec`,
serialise it, and let `QueueingNetworkModelBuilder` produce a Model:

```kotlin
val spec = QueueingNetworkSpec(
    name = "Pharmacy",
    sources = listOf(SourceSpec("Arrivals", exp(1.0), routing = RoutingSpec.Direct("Pharmacist"))),
    stations = listOf(StationSpec("Pharmacist", exp(0.5), capacity = 1, routing = RoutingSpec.Direct("Exit"))),
    sinks = listOf(SinkSpec("Exit")),
)
val toml = QueueingNetworkToml.encode(spec)
val decoded: QueueingNetworkSpec = QueueingNetworkToml.decode(toml)

val model: Model         = QueueingNetworkModelBuilder(spec).build()
val modelFromToml: Model = QueueingNetworkModelBuilder.fromToml(toml).build()
```

`RVData` carries the random-variable description (`RVType` + a
parameter map). The same `QueueingNetworkSpec` types cover every
station archetype.

### ...pick a station archetype

Twenty archetypes ship; the rough decision table:

| Need | Use |
|---|---|
| One queue, one or more parallel servers | **`SingleQStation`** (DSL: `station`) |
| Pure delay (no queue contention) | **`ActivityStation`** (DSL: `delay`) |
| Several queues, one server (e.g. priority) | **`NWayStation`** |
| Resource shared across stations | **`ResourcePoolStation`** (DSL: `pooledStation`) + **`SResourcePool`** |
| Hold a resource across multiple stations | **`SeizeStation`** + **`ReleaseStation`** |
| Form fixed-size batches | **`BatchStation`** |
| Split a batched job back into its members | **`SeparateStation`** |
| Hold while a condition is closed; release on open | **`GateStation`** |
| Finite buffer (block when full) | **`BlockingStation`** |
| Synchronise matching pairs (assembly) | **`MatchStation`** |
| Spawn child jobs, wait for them all, then continue | **`ForkStation`** + **`JoinStation`** |
| Choose a station from a pick rule (e.g. shortest queue) | **`PickStation`** |
| Source of arrivals (homogeneous) | **`SourceStation`** (DSL: `source`) |
| Source of arrivals (non-homogeneous Poisson) | **`NHPPSource`** (DSL: `nhppSource`) |
| Disposal | **`SinkStation`** / **`DisposalStation`** |
| Boundary inlet from another network | **`IngressStation`** |
| Send to another network | **`TransferStation`** |
| Run a `KSLProcess` coroutine as the activity | **`ProcessStation`** (in `ksl.modeling.station.integration`) |

### ...branch on chance / type / condition

Three routing helpers attach to any `Station` in the DSL. They wrap
the corresponding `Router` types.

```kotlin
// Probabilistic — branches must sum to 1.0:
triage.routeByChance(0.8 to fast, 0.2 to slow, streamNum = 6)

// By type id (set by the source's marking hook or by the QObjectClass):
triage.routeByType(1 to serverA, 2 to serverB, default = serverA)

// By predicate, first match wins:
classify.routeByCondition(default = regular) {
    whenever({ q -> q.priority < 5 }, goTo = priority)
}
```

Equivalents for the programmatic and TOML paths exist via
`Router` / `ByTypeRouter` / `ConditionalRouter` / `ProbabilisticRouter`
and the `RoutingSpec` sealed class respectively (`Direct`, `ByChance`,
`ByType`, `ShortestQueue`, `ByCondition`). For the TOML path, the
`ByCondition` cases reference predicates by name from a registry —
the predicate body itself isn't serializable, so you supply it when
constructing the `QueueingNetworkModelBuilder`.

There are also two **selection** helpers:

```kotlin
upstream.routeToShortestQueueOf(serverA, serverB, serverC)
upstream.routeRoundRobinOf(serverA, serverB, serverC)
```

### ...define classes and collect per-class statistics

Create `QObjectClass` instances, attach each as a source's
`qObjectClass`, and (optionally) tell a station to use the class's
value object as its activity time:

```kotlin
val net = StationNetwork(parent, "MC")
val exit = net.sink("Exit")
val server = net.singleQStation("Server", nextReceiver = exit)
server.useQObjectForActivityTime = true

val typeA = QObjectClass(
    "TypeA", typeId = 1,
    valueObject = RandomVariable(parent, ExponentialRV(2.0, 2)),
)
val typeB = QObjectClass(
    "TypeB", typeId = 2,
    valueObject = RandomVariable(parent, ExponentialRV(5.0, 3)),
)

net.source("ArrivalsA", ExponentialRV(8.0, 1), firstReceiver = server, qObjectClass = typeA)
net.source("ArrivalsB", ExponentialRV(8.0, 4), firstReceiver = server, qObjectClass = typeB)

for (className in net.classNames) {
    val systemTime = net.classSystemTime(className)
    val completed = net.classNumCompleted(className)
}
```

The network exposes `classNames`, `classSystemTime(name)`, and
`classNumCompleted(name)` for read-out. In TOML, `QObjectClassSpec`
plays the same role and `SourceSpec.entityClass` references it by
name.

### ...share a resource across multiple stations

A `SResourcePool` is a homogeneous pool of one or more units, seized
and released atomically by a `ResourcePoolStation`:

```kotlin
model.queueingNetwork("Pool") {
    val makers: SResourcePool = pool("Makers", capacity = 2)
    val exit = sink("Exit")
    val making = pooledStation(
        "Making", makers,
        activityTime = UniformRV(15.0, 25.0, streamNum = 2),
        nextReceiver = exit,
    )
    source("Orders", ExponentialRV(60.0, 1)) routeTo making
}
```

For the hold-across-stations pattern — *seize at one station, hold
through intermediate stations, release at another* — use a free-standing
`SResource` and explicit `SeizeStation` / `ReleaseStation`:

```kotlin
model.queueingNetwork("Hold") {
    val packager: SResource = resource("Packager", capacity = 1)
    val exit = sink("Exit")
    val releasePkg = release("ReleasePackager", packager, nextReceiver = exit)
    val pack = delay("Packaging", UniformRV(5.0, 15.0, 2), nextReceiver = releasePkg)
    val paperwork = delay("Paperwork", UniformRV(8.0, 10.0, 3), nextReceiver = pack)
    val seizePkg = seize("SeizePackager", packager, nextReceiver = paperwork)
    source("Arrivals", ExponentialRV(20.0, 1)) routeTo seizePkg
}
```

Each `QObject` carries its outstanding allocations (`SRAllocation`s);
the network releases them in FIFO order per resource.

### ...add a shift / capacity schedule

A station with a built-in capacity (`SingleQStation`) can follow a
repeating `CapacitySchedule`. The schedule lives under your model
element and the station subscribes:

```kotlin
val net = StationNetwork(parent, "Shift")
val exit = net.sink("Exit")
val server = net.singleQStation("Server", ExponentialRV(0.7, 2), nextReceiver = exit)

val schedule = CapacitySchedule(parent, repeatable = true)
schedule.addItem(capacity = 1, duration = 480.0)   // on shift
schedule.addItem(capacity = 0, duration = 480.0)   // off shift
server.useCapacitySchedule(schedule)

net.source("Arrivals", ExponentialRV(1.0, 1), firstReceiver = server)
```

In TOML the same shape is `StationSpec.capacitySchedule =
CapacityScheduleSpec(items, repeatable, startTime)`:

```kotlin
CapacityScheduleSpec(
    items = listOf(
        CapacityItemSpec(capacity = 1, duration = 480.0),
        CapacityItemSpec(capacity = 0, duration = 480.0),
    ),
    repeatable = true,
)
```

Off-shift behaviour follows the IGNORE rule: in-service jobs finish
before the resource drops to zero.

### ...add resource failures

Three failure clocks ship: calendar-time (always running),
operating-time (advances only when busy), and finish-then-fail vs.
preempt-resume effects. The DSL/programmatic call is on
`SingleQStation`:

```kotlin
server.useTimeBasedFailures(
    timeToFailure = ExponentialRV(100.0, 3),
    timeToRepair = ExponentialRV(20.0, 4),
)
```

After simulation, the server's resource exposes the usual responses:

```kotlin
val failedFraction = server.resource.failedStateProportion.acrossReplicationStatistic.average
val numFailures   = server.resource.numTimesFailed.acrossReplicationStatistic.average
val utilization   = server.resource.utilization.acrossReplicationStatistic.average
```

In TOML, the same shape is `StationSpec.failure = FailureSpec.*` (the
sealed class has variants for calendar-time and usage-based failures,
each with a `FailureEffectSpec` of `PREEMPT_RESUME` or
`FINISH_THEN_FAIL`).

### ...model non-homogeneous arrivals

`nhppSource` takes parallel `durations` and `rates` arrays — a
piecewise-constant rate function. The framework uses thinning, so the
rate function must have a known finite supremum (the max of `rates`):

```kotlin
model.queueingNetwork("NHPP") {
    val exit = sink("Exit")
    val server = station("Server", ExponentialRV(0.5, 2), nextReceiver = exit)
    nhppSource(
        "Arrivals",
        durations = doubleArrayOf(60.0, 60.0, 60.0, 60.0),
        rates     = doubleArrayOf(1.0,  3.0,  2.0,  0.5),
        firstReceiver = server,
        streamNum = 1,
    )
}
```

The four-hour pattern repeats indefinitely.

### ...fork / join (and batch / separate)

**Fork / join** spawns child `QObject`s, waits for them all to reach
the join, then continues the parent. The fork takes a
`ChildCountIfc` (often "however many the parent's marking says"), a
child-side receiver, and the join station for synchronisation. The
join exposes `parentInput()` and `childInput()` so each side wires
explicitly:

```kotlin
model.queueingNetwork("ForkJoin") {
    val exit = sink("Exit")
    val finish = delay("Finish", UniformRV(2.0, 4.0, 2), nextReceiver = exit)
    val joiner = join("Join", nextReceiver = finish)

    val childWork = delay(
        "ChildWork", UniformRV(15.0, 25.0, 3),
        nextReceiver = joiner.childInput(),
    )
    val parentWait = delay(
        "ParentWait", UniformRV(8.0, 10.0, 4),
        nextReceiver = joiner.parentInput(),
    )

    val forker = fork(
        name = "Fork", join = joiner,
        childCount = ChildCountIfc { p -> p.qObjectType },
        childReceiver = childWork,
        nextReceiver = parentWait,
    )
    source(
        "Orders", ExponentialRV(60.0, 1), firstReceiver = forker,
        marking = { q -> q.qObjectType = 3 },
    )
}
```

**Batch / separate** is the simpler aggregation pair: gather a fixed
number of arrivals into one batched `QObject`, ship as a unit, then
break apart:

```kotlin
model.queueingNetwork("BatchSeparate") {
    val exit = sink("Exit")
    val unpack = separate("Unpack", nextReceiver = exit)
    val ship = delay("Ship", UniformRV(5.0, 10.0, 2), nextReceiver = unpack)
    val pack = batch("Pack", batchSize = 5, nextReceiver = ship)
    source("Items", ExponentialRV(2.0, 1), firstReceiver = pack)
}
```

### ...bridge to the process view

A `ProcessStation`'s "activity" is a `KSLProcess` coroutine, not a
fixed delay. Use it when a step is most naturally expressed as
suspending process-view code (seize, hold, delay, signal):

```kotlin
val net = StationNetwork(parent, "Bridge")
val exit = net.sink("Exit")

val complex = net.processStation("Complex", nextReceiver = exit) { item ->
    // body is a KSLProcess: can seize resources, delay, signal, etc.
    delay(2.0)
}
net.source("Arrivals", ExponentialRV(1.0, 1), firstReceiver = complex)
```

`processStation` is an extension on `StationNetwork` and lives in
`ksl.modeling.station.integration` so the core package stays
coroutine-free. The carrier entity is transient; when the process
completes, the original `QObject` is sent onward.

### ...connect two networks

`IngressStation` is a named inbound port; `TransferStation` is the
outbound counterpart that hands a `QObject` to the ingress of another
network. Together they let you split a model into independently-
authored sub-networks while keeping each network's statistics
self-contained.

### ...read results

The network surface (`StationNetworkCIfc`) exposes the network-level
aggregates:

```kotlin
val avgN       = net.numInSystem.acrossReplicationStatistic.average
val avgT       = net.systemTime.acrossReplicationStatistic.average
val throughput = net.numCompleted.acrossReplicationStatistic.average

for (className in net.classNames) {
    val classT = net.classSystemTime(className)?.acrossReplicationStatistic?.average
}
```

Each station exposes its own queue, resource (where applicable),
`numInStation`, and `stationTime` responses. Resources expose
`utilization`, `failedStateProportion`, `numTimesFailed`, and so on.
The half-width summary
(`model.simulationReporter.printHalfWidthSummaryReport()`) lists every
response in the model — useful for sanity-checking; reach for the
DSL in `ksl.utilities.io.report` if you want a structured Markdown /
HTML view.

---

## 5. The key types at a glance

A compact tour grouped by what you reach for. Member detail is on the
Dokka pages.

**Network top-level**

- `StationNetwork` — the runtime network. Lives under a
  `ModelElement` parent (typically the `Model`).
- `StationNetworkCIfc` — the read-only handle (`numInSystem`,
  `systemTime`, `numCompleted`, `classNames`, …).
- `queueingNetwork(name) { … }` — DSL extension on `ModelElement`.
- `QueueingNetworkSpec` — the serializable canonical description.
- `QueueingNetworkModelBuilder` — the single consumer:
  `build()` from a spec, `fromToml(text).build()` from a file.
- `QueueingNetworkToml`, `QueueingNetworkJson` — codecs.

**Sources & sinks**

- `SourceStation` — homogeneous arrivals.
- `NHPPSource` — non-homogeneous Poisson arrivals (piecewise-constant
  rate function via parallel `durations` / `rates` arrays).
- `SinkStation`, `DisposalStation` — terminate flow; both retire
  `QObject`s.

**Service stations**

- `SingleQStation` — one queue, *N* parallel servers (`capacity`).
- `ActivityStation` — pure delay (no resource contention).
- `NWayStation` — multiple queues fed into one server (priority,
  multi-class).
- `ResourcePoolStation` — service via a `SResourcePool`.
- `PickStation` — chooses a target based on a pick rule.

**Flow-control stations**

- `BatchStation`, `SeparateStation` — aggregate and disaggregate.
- `GateStation`, `BlockingStation` — block while closed / when buffer
  full.
- `MatchStation` — synchronise on a pairing key.
- `ForkStation`, `JoinStation` — spawn-and-wait; `JoinStation`
  exposes `parentInput()` and `childInput()`.
- `SeizeStation`, `ReleaseStation` — hold a resource across stations.

**Boundary / integration**

- `IngressStation`, `TransferStation` — network-to-network ports.
- `ProcessStation` — KSLProcess coroutine as the station's activity
  (in `ksl.modeling.station.integration`).

**Routing**

- `Route` — a fixed sequence of receivers.
- `Router`, `ByTypeRouter`, `ConditionalRouter`,
  `ProbabilisticRouter`, `ShortestQueueRouter`, `RoundRobinRouter` —
  the implementations.
- The DSL helpers: `routeTo`, `routeByChance`, `routeByType`,
  `routeByCondition`, `routeToShortestQueueOf`, `routeRoundRobinOf`,
  `route(name, *steps)`.

**Resources**

- `SResource` — a homogeneous resource with `capacity`.
- `SResourcePool` — a named pool of one or more `SResource`s.
- `SRAllocation` — outstanding seize token (carried by `QObject`).
- `CapacitySchedule` (from `ksl.modeling.entity`) and
  `CapacityScheduleSpec` (TOML).
- `FailureSpec`, `FailureEffectSpec` — calendar-time / usage-based;
  `PREEMPT_RESUME` or `FINISH_THEN_FAIL`.
- `SetupTime`, `SetupSpec`, `SetupEntry`, `InitialSetupEntry` —
  sequence-dependent setups.

**Multi-class**

- `QObjectClass` — runtime class definition (typeId + optional value
  object).
- `QObjectClassSpec` — the TOML/JSON twin.

**Marking hooks**

- `SourceMarkingHook` / `MarkingHookIfc` — set attributes on each
  newly-created `QObject` at its source (the escape hatch for
  class-by-rule).

---

## 6. Gotchas & best practices

- **`SingleQStation.capacity` is the server count, not the queue
  capacity.** The queue is unbounded by default. Use `BlockingStation`
  for a finite buffer.
- **Routing helpers replace `nextReceiver`.** Calling
  `routeByChance` (or any of the others) attaches a router and points
  the station at it — don't *also* set `nextReceiver`.
- **`Route` is a fixed sequence**, not a generalized graph. If any
  step diverges (by chance or condition), put a router-bearing
  station in the route, not a `Route`.
- **Seize / release must pair.** The network tracks per-`QObject`
  allocations and validates them at run end. If you `seize` and don't
  `release`, you'll see a non-zero `holdingsAtRunEnd`.
- **`useTimeBasedFailures` is calendar-clock** — it runs whether the
  server is busy or idle. For "usage-based" failures (clock runs only
  during service), use the usage-based failure spec / API.
- **NHPP rate functions must be non-negative and have a finite
  supremum.** The framework uses thinning against `max(rates)`; an
  unbounded rate function will not work.
- **`ProcessStation` keeps the coroutine deterministic w.r.t. its
  random sources.** Any randomness inside the activity body should
  come from KSL streams just like in any other process-view code.
- **Multi-class statistics require classes to be created before the
  source that uses them.** The DSL handles this naturally; the
  programmatic API does too as long as you construct the classes in
  order. If `classNames` is empty after `simulate()`, you forgot to
  attach a class to a source.

---

## 7. See also

**Related KSL packages**

- `ksl.modeling.entity` — the process-view neighbor, bridged via
  `ProcessStation`. Reach for process view when each job's logic is
  best expressed as a suspending script.
- `ksl.modeling.variable` — the `Response` / `Counter` / `TWResponse`
  types every station metric is built on.
- `ksl.modeling.queue` — what station queues are built from.
- `ksl.utilities.random` — inter-arrivals, service times, failure
  clocks.
- `ksl.utilities.io.report` — assemble a structured Markdown / HTML
  report from any network's responses.

**Other usage guides** *(in `docs/guides/`)*

- `ksl-modeling.md` — the underlying modeling primitives.
- `ksl-supplychain.md` — the supply-chain domain layer; uses the same
  data-driven DSL/DTO pattern this package uses.
- `ksl-utilities-random.md` — stream and distribution control.
- `ksl-simulation.md` — the `Model` lifecycle this package runs
  inside.

**Examples** *(under `KSLExamples/.../models/station/`)*

| Example | Showcases |
|---|---|
| `DriveThroughPharmacyStation` / `…Dsl` / `…Dto` | the same model in three authoring forms |
| `TieDyeStation` / `…Dsl` / `…Dto` | fork / join + shared `SResource` (hold-across-stations) |
| `StemFairMixerStation` / `…Dsl` / `…Dto` | multi-class + by-type routing |
| `TestAndRepairStation` / `…Dsl` | sequenced `Route`s and resource pools |
| `StemFairEnhancedStation` | NHPP source + capacity schedules |
| `StationNetworkTandemQueue` | minimal tandem queue |
| `StationNetworkParallelServers` | shortest-queue routing |
| `StationNetworkMultiClass` | per-class statistics readout |
| `StationNetworkWithFailures` | calendar-time failures |
| `StationNetworkWithShift` | repeating capacity schedule (on/off shift) |
| `StationNetworkDslExample` | broad DSL surface tour |
| `StationNetworkFromTomlExample` | load → build → run from a TOML file |

**KSL Book**

For background on replications, warmup, output analysis, and variance
reduction, see [the KSL Book](https://rossetti.github.io/KSLBook/).
