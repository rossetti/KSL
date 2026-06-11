# Using `ksl.modeling.agent`

A task-oriented usage guide. For each common task, the smallest amount
of code that does it, and the gotchas that matter in practice.
Reference detail (parameter lists, every overload) is on the Dokka API
pages; this guide gets you productive.

> **Status: experimental.** `ksl.modeling.agent` is released as
> experimental. Its public API may change in future releases without
> notice. Pin your KSL version if you build models against it for
> production use.

## 1. What this package is for

`ksl.modeling.agent` is a first-class **agent-based modeling layer**
for KSL, peer to the process view in `ksl.modeling.entity`. Agents are
long-lived, autonomous, reactive actors: they perceive (via spatial
queries or messages), decide (via a statechart), and act (move, send
messages, seize resources, transition state).

Reach for it when the natural language of your model is *"an actor
reacts to inputs and changes state"* — a forklift that takes breaks, a
pedestrian that steers around obstacles, a drone that bids on tasks, a
disease that spreads on a contact network. Reach elsewhere when a
different mental model fits better:

- `ksl.modeling.entity` is the process view. Each entity's logic is a
  suspending coroutine — `delay`, `seize`, `release`, `hold`. Use it
  when the script for a single entity is the cleanest expression of
  its behavior. Use this package when reactivity (state changes
  triggered by signals, messages, conditions) is the dominant idiom.
- `ksl.modeling.station` is the queueing-network view: jobs flow
  through passive stations. Use it when the topology *is* the
  control flow.

The three views interoperate. `AgentResource` is also a
`ResourceWithQ`, so process-view code can `seize` and `release` it
while the agent runs its own statechart underneath. `SpatialBridge`
adapts `ksl.modeling.spatial` for shared coordinates.

### How it relates to its neighbors

- `ksl.modeling.entity` — bridged via `AgentResource` /
  `MovableAgentResource`; `KSLProcess` bodies host `sendMessage`,
  `travelTo`, and the `contractNet` helper.
- `ksl.modeling.spatial` — the alternative spatial substrate. Use
  `SpatialBridge` to share coordinates between an `AgentModel`
  projection and a `SpatialModel`.
- `ksl.modeling.variable` — every per-agent or per-population metric
  is a `Response` / `Counter` / `TWResponse`.
- `ksl.utilities.random` — stochastic decisions (`defaultRNStream`
  inside an `AgentModel`), force jitter, randomized populations.

### Two orthogonal axes

```
                          ┌──────────────────────────────────────┐
                          │           Behavior axis              │
                          │   Statechart + AgentMessaging         │
                          │   (signals, conditions, timeouts,     │
                          │    messages, hierarchical states)     │
                          └───────────────┬──────────────────────┘
                                          │
   ┌──────────────────────────────────────┴──────────────────────────────────────┐
   │                                                                             │
   │   AgentModel  ──►  { transient Agent, PermanentAgent, AgentResource }        │
   │                                                                             │
   │   ────── Optional spatial axis ──────                                       │
   │   Projection<A> ── Grid / Network / Continuous (2D)                          │
   │                 ── Voxel  / ContinuousVolume     (3D)                        │
   │   + optional Movement primitives (travelTo, FlowField, Travel3D)            │
   │   + optional Force-based Dynamics (separation, alignment, cohesion, …)      │
   │                                                                             │
   └─────────────────────────────────────────────────────────────────────────────┘
```

Pick what you need; everything is independent. A pure reactive model
uses just the behavior axis; a flock with no messages uses just
spatial + dynamics.

---

## 2. The mental model

### Two tiers of agent

- **Transient `Agent`** — a `QObject`-based actor created *during* a
  replication, typically with its own `process { … }` body. No
  automatic per-agent statistics. Use it for arrival-driven
  populations, swarms, ad-hoc workers.
- **Permanent `PermanentAgent`** — a `ModelElement` declared *at setup
  time*, with full KSL lifecycle hooks and opt-in per-agent stats
  (state-time, mailbox stats). Use it for named role-holders or any
  agent you want individual stats on.
- **`AgentResource`** — a `ResourceWithQ` that is also an agent. It
  has a mailbox, an optional statechart, and `goOnShift` /
  `goOffShift` for breaks. Process-view code can `seize` and
  `release` it.

All three implement `AgentLike` so the same `Statechart` and `AgentMailbox`
machinery serves all of them.

### Behavior is a statechart, not a script

Every agent can carry an optional `Statechart`. States have entry /
exit actions; transitions fire on timeouts, conditions, signals, and
typed messages. Substates compose (hierarchical state machines), and
`final` states with an `onCompletion` hook close out the agent's
behavior. Action handlers are *not* coroutines — they run on the
executive. For suspending work, transition to a state that triggers a
separate `process { … }` body.

### Communication is messages on a shared bus

`AgentMessage` is a sealed hierarchy: `Inform<P>`, `Request<P>`,
`Propose<P>`, `Accept`, `Reject`, `Cancel`. Each carries a `from`
(the sender entity) and an optional `conversationId`. Mailboxes are
*POJOs* that filter the shared bus by recipient reference; this is
what makes transient agents cheap (no `ModelElement` per agent).
Inside a process body, send with `sendMessage(msg, recipient.mailbox)`
(suspending). Inside a statechart action, deliver directly with
`recipient.mailbox.deliver(msg)` (non-suspending).

### Space is a `Projection`, not coordinates

Projections plug different topologies into the same place / move /
neighborhood-query surface:

- `GridProjection<A>` — 2D cell lattice; toroidal optionally;
  `mooreNeighborhood` and `vonNeumannNeighborhood`.
- `NetworkProjection<A>` — directed or undirected graph; `connect`,
  `neighborsOf`, `reachableFrom`.
- `ContinuousProjection<A>` — `R²` with a spatial-hash index;
  `placeAt`, `moveTo`, `agentsWithin(point, radius)`.

3D variants (`VoxelProjection`, `ContinuousVolume`) are siblings —
same surface, more dimension.

### Movement is suspending

`travelTo(agent, space, destination, velocity)` is a
`KSLProcessBuilder` extension that interpolates the agent's position
in the projection over simulated time. `travelThrough` does the same
along a path. Use `startTravel` + `awaitTravel` for interruptible
travel (cancel, preempt, change destination mid-journey).

### Dynamics is optional

A `Dynamics<A>` layer on a `ContinuousProjection` applies named
`Force`s each step and integrates velocity. Standard force factories
(`separation`, `alignment`, `cohesion`, `peerRepulsion`,
`wallRepulsion`, `desiredVelocity`, `viscousDrag`) cover the textbook
flocking and social-force patterns. `Dynamics3D` is the volumetric
equivalent.

---

## 3. Quick start

The smallest runnable agent model: one permanent agent toggling
between two states on a timer. Compiles and runs as-is:

```kotlin
import ksl.modeling.agent.AgentModel
import ksl.simulation.Model
import ksl.simulation.ModelElement

class HeartbeatModel(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    inner class Beat(aName: String) : PermanentAgent(aName) {
        init {
            statechart {
                initial("Off")
                state("Off") { onTimeout(1.0) { transitionTo("On") } }
                state("On")  { onTimeout(1.0) { transitionTo("Off") } }
            }
        }
    }
    val beat = Beat("Beat-1")
}

fun main() {
    val model = Model("Heartbeat")
    HeartbeatModel(model)
    model.numberOfReplications = 1
    model.lengthOfReplication = 20.0
    model.simulate()
    model.print()
}
```

What this shows: `AgentModel` as a peer to `ProcessModel`; one
`PermanentAgent` declared at setup time; a two-state statechart driven
by `onTimeout`. The rest of the package is variations on these
primitives.

---

## 4. How do I…?

### ...add a transient `Agent` (created during a replication)

A transient `Agent` is a `QObject`-based actor created *during* a
replication, typically with its own `process { … }` body. No
`ModelElement` is constructed, so creation is cheap and dynamic:

```kotlin
class WorkflowModel(parent: ModelElement) : AgentModel(parent) {
    inner class Job(aName: String) : Agent(aName) {
        val script: KSLProcess = process(isDefaultProcess = true) {
            delay(5.0)
            // ... work ...
        }
    }
}
```

`isDefaultProcess = true` says "this is the process that runs when
the agent is activated." Create `Job` instances on demand (from a
source's `marking` hook, from another agent's process body, from an
`initialize()` callback for the first batch).

### ...author a statechart

`statechart { … }` is the DSL entry point on `Agent` /
`PermanentAgent` / `AgentResource`. Inside, `initial(name)` sets the
starting state; each `state(name) { … }` defines triggers. Substates
nest. `final(name) { … }` plus `onCompletion { … }` close out the
chart:

```kotlin
class ControllerModel(parent: ModelElement) : AgentModel(parent) {

    val signal: AgentSignal = AgentSignal("WakeUp")

    inner class Controller(aName: String) : PermanentAgent(aName) {
        var temperature: Double = 0.0

        init {
            statechart {
                initial("Idle")
                state("Idle") {
                    onEntry { /* log entry */ }
                    onSignal(signal) { transitionTo("Active") }
                }
                state("Active") {
                    initial("Warmup")
                    state("Warmup") {
                        onTimeout(2.0) { transitionTo("Running") }
                    }
                    state("Running") {
                        onCondition({ temperature > 100.0 }) {
                            transitionTo("Cooling")
                        }
                    }
                    state("Cooling") {
                        onTimeout(5.0) { transitionTo("Idle") }
                    }
                }
                final("Done")
                onCompletion { name -> /* react to final state */ }
            }
        }
    }
}
```

Five trigger kinds ship: `onEntry`, `onExit`, `onTimeout(duration)`,
`onCondition({ … })`, `onSignal(signal)`, `onMessage<T>({ … })`.
`transitionTo(stateName)` fires a transition from inside any handler.
`onCondition` is polled at the executive's condition-action interval,
not continuously.

### ...send and receive messages

Define a recipient with an `onMessage` trigger; from a transient
`Agent`'s `process` body, send via `sendMessage` (suspending):

```kotlin
class TwoAgentChat(parent: ModelElement) : AgentModel(parent) {

    inner class Listener(aName: String) : PermanentAgent(aName) {
        init {
            statechart {
                initial("Listen")
                state("Listen") {
                    onMessage<AgentMessage.Inform<String>> { msg ->
                        val payload: String = msg.payload
                        // react to the message
                    }
                }
            }
        }
    }
    val listener = Listener("Listener")

    inner class Talker(aName: String) : Agent(aName) {
        val script: KSLProcess = process(isDefaultProcess = true) {
            delay(1.0)
            sendMessage(
                AgentMessage.Inform(this@Talker, payload = "hello"),
                listener.mailbox,
            )
        }
    }
}
```

`AgentMessage` is sealed; the variants are `Inform<P>`, `Request<P>`,
`Propose<P>`, `Accept`, `Reject`, `Cancel`. From a statechart action
(non-coroutine), use `recipient.mailbox.deliver(msg)` instead — it
doesn't suspend.

### ...group agents in a `Context` + `Population`

A `Context` is a named, replication-aware container. A `Population`
is a typed, iterable view filtered by class:

```kotlin
class PopulationModel(parent: ModelElement) : AgentModel(parent) {
    inner class Walker(aName: String) : Agent(aName)

    val walkers: Context<Walker> = Context("walkers")

    override fun initialize() {
        super.initialize()
        for (i in 1..50) walkers.add(Walker("w-$i"))
    }

    fun headcount(): Int = walkers.size

    // Population: a live view filtered by type, iterable / queryable.
    val population: Population<Walker> = Population(this, Walker::class.java)
}
```

`Context` captures its setup-time membership and restores it at the
start of every replication, so permanent scaffolding doesn't have to
be re-added each run. Transient agents added during a replication
disappear at replication end.

### ...place agents in a spatial projection

Three 2D projections share a common surface (`placeAt`, `moveTo`,
`positionOf`, agent-set queries). Pick the one whose topology fits
the model.

**Grid:**

```kotlin
class GridDemo(parent: ModelElement) : AgentModel(parent) {
    inner class Bug(aName: String) : Agent(aName)

    val bugs: Context<Bug> = Context("bugs")
    val grid = GridProjection(bugs, columns = 20, rows = 20, torus = true)

    init {
        val a = Bug("a"); val b = Bug("b")
        bugs.add(a); bugs.add(b)
        grid.placeAt(a, Cell(0, 0))
        grid.placeAt(b, col = 1, row = 0)

        val neighbors = grid.mooreNeighborhood(Cell(0, 0))
        val others = grid.agentsAt(0, 0)
        val nearby = grid.agentsWithin(Cell(0, 0), radius = 2)
    }
}
```

**Network:**

```kotlin
class NetworkDemo(parent: ModelElement) : AgentModel(parent) {
    inner class Person(aName: String) : Agent(aName)

    val people: Context<Person> = Context("people")
    val friendships = NetworkProjection(people, directed = false)

    init {
        val a = Person("a"); val b = Person("b")
        people.add(a); people.add(b)
        friendships.connect(a, b)

        val friends = friendships.neighborsOf(a)
        val reachable = friendships.reachableFrom(a)
    }
}
```

**Continuous (2D):**

```kotlin
class ContinuousDemo(parent: ModelElement) : AgentModel(parent) {
    inner class Boid(aName: String) : Agent(aName)

    val boids: Context<Boid> = Context("boids")
    val space = ContinuousProjection(
        context = boids,
        xRange = 0.0..100.0,
        yRange = 0.0..100.0,
        torus = true,
    )

    init {
        val b = Boid("b1")
        boids.add(b)
        space.placeAt(b, x = 10.0, y = 20.0)
        space.moveTo(b, Point2D(11.0, 20.5))
        val pos: Point2D? = space.positionOf(b)
    }
}
```

### ...move with `travelTo`

From inside a `process { … }` body, suspending. The agent's position
is interpolated in `space` over simulated time:

```kotlin
class TravelerModel(parent: ModelElement) : AgentModel(parent) {
    inner class Traveler(aName: String) : Agent(aName)

    val travelers = Context<Traveler>("travelers")
    val space = ContinuousProjection(travelers, 0.0..100.0, 0.0..100.0)

    fun makeTraveler(): Traveler {
        val t = Traveler("t")
        travelers.add(t)
        space.placeAt(t, 0.0, 0.0)
        // launch its journey from a transient agent's process body
        object : Agent("driver") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                travelTo(t, space, destination = Point2D(50.0, 50.0), velocity = 1.0)
            }
        }
        return t
    }
}
```

For paths, use `travelThrough(agent, space, waypoints, velocity)`.
For interruptible movement, separate launch from join:
`val handle = startTravel(…); /* do other things */; val result = awaitTravel(handle)`.
`Travel3D` exposes the 3D counterparts.

### ...apply force-based dynamics (flocking, social force)

Stack a `Dynamics<A>` layer on a `ContinuousProjection`, add named
`Force`s, then step every agent each tick:

```kotlin
class FlockModel(parent: ModelElement) : AgentModel(parent) {
    inner class Bird(aName: String) : Agent(aName)

    val birds: Context<Bird> = Context("birds")
    val space = ContinuousProjection(birds, 0.0..200.0, 0.0..200.0, torus = true)
    val dynamics = Dynamics(space)

    init {
        dynamics.addForce(separation<Bird>(radius = 5.0))
        dynamics.addForce(alignment<Bird>(radius = 15.0))
        dynamics.addForce(cohesion<Bird>(radius = 30.0))
        // Each tick: dynamics.stepAll(birds.members, dt = 0.1)
    }
}
```

`Force` factories that ship: `separation`, `alignment`, `cohesion`
(the Reynolds-flocking trio), `peerRepulsion`, `wallRepulsion`,
`desiredVelocity`, `viscousDrag`, `constantForce`, `weighted`. Custom
forces are one-liners (`Force { agent, dynamics, dt -> … }`).

### ...delegate work with Contract-Net

The classic announce / bid / award pattern. Inside an initiator
agent's `process` body, broadcast a `Request`, wait for `Propose`
replies until the deadline, and pick the best. The bidders are
transient `Agent`s whose own statecharts or process bodies reply with
`AgentMessage.Propose`; this snippet shows the initiator side:

```kotlin
class TaskAuctionModel(parent: ModelElement) : AgentModel(parent) {
    data class Bid(val priceCents: Int)

    inner class Bidder(aName: String) : Agent(aName)

    inner class Initiator(aName: String) : Agent(aName) {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = Bidder("A"); val b = Bidder("B")
            val outcome = contractNet<String, Bid>(
                bidders = listOf(a, b),
                callForProposals = "haul-load-#42",
                deadline = 1.0,
                selectBest = { ps -> ps.minByOrNull { it.proposal.priceCents } },
            )
            outcome?.winner?.let { /* award the job */ }
        }
    }
}
```

The helper reserves the initiator's mailbox for the conversation
before broadcasting, so a `onMessage<Propose>` handler elsewhere on
the same mailbox can't consume bids out from under it.

### ...make an agent a seizable resource

`AgentResource` is-a `ResourceWithQ` *and* an `AgentLike`. Process-view
code can `seize` it normally while its statechart governs its own
reactive behavior (taking breaks, refusing while off-shift):

```kotlin
class ForkliftModel(parent: ModelElement) : AgentModel(parent) {

    inner class Forklift(aName: String) : AgentResource(
        agentModel = this@ForkliftModel,
        name = aName,
        capacity = 1,
    ) {
        init {
            statechart {
                initial("on")
                state("on") {
                    onMessage<AgentMessage.Inform<String>>({ it.payload == "break" }) {
                        goOffShift()
                        transitionTo("off")
                    }
                }
                state("off") {
                    onMessage<AgentMessage.Inform<String>>({ it.payload == "resume" }) {
                        goOnShift()
                        transitionTo("on")
                    }
                }
            }
        }
    }
    val forklift = Forklift("F-1")
    // Process-view code can now `seize(forklift)` / `release(forklift)`.
}
```

`goOffShift()` drops capacity to zero (no new seizes; in-flight
allocations finish normally). `goOnShift()` restores the on-shift
capacity. For movement-aware versions use `MovableAgentResource`,
which composes `AgentResource` with a `ContinuousProjection`.

### ...go 3D

The 3D types are siblings of the 2D ones — same surface, one more
dimension. `ContinuousVolume` is the volumetric `ContinuousProjection`;
`VoxelProjection` is the volumetric `GridProjection`:

```kotlin
class SkyDemo(parent: ModelElement) : AgentModel(parent) {
    inner class Drone(aName: String) : Agent(aName)

    val drones: Context<Drone> = Context("drones")
    val sky = ContinuousVolume(
        drones,
        xRange = 0.0..100.0,
        yRange = 0.0..100.0,
        zRange = 0.0..50.0,
    )

    init {
        val d = Drone("d1")
        drones.add(d)
        sky.placeAt(d, Point3D(10.0, 20.0, 5.0))
        val pos: Point3D? = sky.positionOf(d)
    }
}

class VoxelDemo(parent: ModelElement) : AgentModel(parent) {
    inner class Block(aName: String) : Agent(aName)

    val blocks: Context<Block> = Context("blocks")
    val voxels = VoxelProjection(blocks, columns = 10, rows = 10, layers = 5)

    init {
        val b = Block("b1")
        blocks.add(b)
        voxels.placeAt(b, Voxel(0, 0, 0))
    }
}
```

`FlowField3D`, `Travel3D` (`travelTo3D` / `travelThrough3D`),
`Dynamics3D`, and `Force3D` / `Forces3D` are the corresponding
movement and dynamics primitives in 3D.

### ...validate configuration with property delegates

The package ships read/write-property delegates that enforce range
constraints on every assignment. Use them for any tunable knob the
user might set from outside:

```kotlin
class TunableModel(parent: ModelElement) : AgentModel(parent) {

    var stepSize: Double by positive(1.0)
    var populationCount: Int by positive(50)
    var infectionProb: Double by probability(0.10)
    var initialInfected: Int by nonNegative(3)

    companion object Defaults {
        var defaultStepSize: Double by positive(1.0)
        var defaultPopulation: Int by positive(50)
    }
}
```

`positive(initial)`, `nonNegative(initial)`, `probability(initial)`
(in `[0, 1]`). Assigning an out-of-range value throws
`IllegalArgumentException`. The companion-object `Defaults` pattern
makes class-level tunables overridable too.

### ...read per-agent statistics and observe agent registration

`AgentModel.agentCount` and the `agents` list give a network-level
view of setup-time agents. `PermanentAgent` instances opt into an
`AgentPerformance` companion that exposes statechart-state time and
mailbox stats. For external integration (e.g. visualization),
register an `AgentRegistryObserver`:

```kotlin
fun readStats(model: AgentModel) {
    val total: Int = model.agentCount
    for (agent in model.agents) {
        // Per-agent state-time and mailbox stats are exposed by
        // PermanentAgent.performance if it was opted in.
    }
    model.attachRegistryObserver(object : AgentModel.AgentRegistryObserver {
        override fun onAgentRegistered(agent: AgentLike) { /* log */ }
    })
}
```

Transient `Agent`s don't appear in `model.agents` — they aren't
`ModelElement`s. Track them in a `Context` / `Population` instead.

---

## 5. The key types at a glance

A compact tour, grouped by what you reach for. Member-level detail is
on the Dokka pages.

**Model & lifecycle**

- `AgentModel` — the entry point; a `ProcessModel` subclass.
- `AgentLike` — the trait shared by every kind of agent (`mailbox`,
  `name`, `currentTime`, optional `statechart`).
- Three agent kinds (all inner classes of `AgentModel`): `Agent`
  (transient), `PermanentAgent` (setup-time with stats),
  `AgentResource` (seizable; also a `ResourceWithQ`).
- `MovableAgentResource` — `AgentResource` whose position lives in a
  `ContinuousProjection`.
- `AgentRegistryObserver` — external hook for agent-registration
  events.

**Behavior**

- `Statechart`, `StatechartBuilder`, `StateBuilder`, `StateAction` —
  the DSL surface.
- `AgentSignal` — name-typed signal value passed to `onSignal`.

**Messaging**

- `AgentMessage` (sealed: `Inform<P>`, `Request<P>`, `Propose<P>`,
  `Accept`, `Reject`, `Cancel`).
- `AgentMailbox` — POJO recipient filter on the shared bus.
- `contractNet<Q, B>(bidders, callForProposals, deadline, selectBest)`
  — the announce / bid / award helper.
- `sendMessage(msg, mailbox)` — suspending; for `process { … }`.
- `mailbox.deliver(msg)` — non-suspending; for statechart actions.

**Population & spatial context**

- `Context<A>` — replication-aware named container.
- `Population<A>` — typed, iterable, queryable view (live-filtered or
  snapshotted).
- `Projection<A>` — the interface every spatial substrate implements.

**2D spatial**

- `GridProjection<A>`, `Cell`, `GridMetric`, `GridGraph` — lattice
  topology with Moore / Von Neumann neighborhoods.
- `NetworkProjection<A>` — directed or undirected graph with
  `connect`, `neighborsOf`, `reachableFrom`.
- `ContinuousProjection<A>`, `Point2D` — `R²` with spatial-hash
  indexing.

**3D spatial**

- `ContinuousVolume<A>`, `Point3D` — `R³` with spatial-hash indexing.
- `VoxelProjection<A>`, `Voxel`, `VoxelGraph` — 3D lattice with
  Chebyshev3D / Manhattan3D / Euclidean3D / Octile3D metrics.

**Movement**

- `travelTo` / `travelThrough` (and the 3D pair) — suspending
  primitives on `KSLProcessBuilder`.
- `startTravel` + `awaitTravel`, `TravelHandle`, `TravelResult` — for
  interruptible / preemptable travel.

**Dynamics & flow fields**

- `Force<A>`, `Forces` (factory functions: `separation`, `alignment`,
  `cohesion`, `peerRepulsion`, `wallRepulsion`, `desiredVelocity`,
  `viscousDrag`, `constantForce`, `weighted`).
- `Dynamics<A>` — adds forces, integrates velocity, bounds `minSpeed`
  / `maxSpeed`.
- `FlowField`, `FlowField3D` — distance-from-sources gradient fields
  for steering.
- `JitterDirection` — deterministic small-angle jitter for breaking
  coincident-force ties.

**Bridges & validation**

- `SpatialBridge` — adapter to `ksl.modeling.spatial`.
- `positive(initial)`, `nonNegative(initial)`, `probability(initial)`
  — property-delegate factories that validate on each assignment.

---

## 6. Gotchas & best practices

- **Transient agents have no automatic per-agent stats.** Use
  `PermanentAgent` (with its opt-in `AgentPerformance`) when you want
  individual state-time or mailbox stats. Use a `Context` /
  `Population` to track transient cohorts.
- **`onCondition` is polled, not continuous.** It fires when the
  executive evaluates conditional actions, which is the same point
  every conditional action in KSL is evaluated. For per-event
  triggers, prefer `onSignal` or `onMessage`.
- **Statechart actions run on the executive, not in a coroutine.**
  Don't call suspending functions inside `onEntry` / `onTimeout` / etc.
  For suspending work, transition to a state that triggers a separate
  `process { … }` body, or use `mailbox.deliver` (non-suspending) for
  send-from-statechart.
- **Messages are filtered by mailbox reference**, not by name. Send
  to the wrong `mailbox` and the message is silently dropped — no
  recipient matches and no error fires.
- **Coincident agents under force-based dynamics need jitter.** Two
  agents at the same position produce a zero separation force; use
  `JitterDirection` with an explicit `RNStream` to break the tie
  reproducibly.
- **Statechart timeouts schedule executive events.** Leaving the
  state cancels the pending timeout; re-entering schedules a fresh
  one. Don't rely on the same `onTimeout` firing across exits and
  re-entries.
- **`AgentResource` grants are not vetoable per request in v1.** Use
  `goOffShift()` for "refuse new work"; for finer policy, fall back
  to process-view seize logic.
- **2D and 3D types live in the same package.** `Point2D` and
  `Point3D`, `FlowField` and `FlowField3D`, etc. — import the variant
  you need, don't mix dimensions on one projection.

---

## 7. See also

**Related KSL packages**

- `ksl.modeling.entity` — the process view; bridged via
  `AgentResource`. Reach for it when a single entity's behavior is
  best written as a suspending script.
- `ksl.modeling.station` — the queueing-network view; reach for it
  when the topology *is* the control flow.
- `ksl.modeling.spatial` — the alternative spatial substrate; bridged
  via `SpatialBridge` for shared coordinates.
- `ksl.modeling.variable` — the `Response` / `Counter` / `TWResponse`
  types every per-agent metric is built on.
- `ksl.utilities.random` — `defaultRNStream`, distributions, jitter.

**Other usage guides** *(in `docs/guides/`)*

- `ksl-modeling.md` — the underlying modeling primitives.
- `ksl-station.md`, `ksl-supplychain.md` — sibling domain layers.
- `ksl-utilities-random.md` — stream and distribution control.
- `ksl-simulation.md` — the `Model` lifecycle this package runs
  inside.

**Examples** *(under `KSLExamples/.../agent/`)*

| Example | Showcases |
|---|---|
| `GridEpidemicExample` | grid projection + Moore neighborhoods + SIR dynamics |
| `NetworkRumorExample` | network projection + reachability + independent-cascade diffusion |
| `CorridorPedestrianExample` | continuous projection + social-force pedestrians |
| `PedestrianCrowdExample` | force-based dynamics on a richer 2D space |
| `BuildingEvacuationExample` | FlowField-guided steering |
| `FlockingExample` | Reynolds boids (separation + alignment + cohesion) |
| `WarehouseAGVExample` | `MovableAgentResource` on a grid; AGV routing |
| `AutonomousDeliveryExample` | continuous space + travel + contract-net |
| `AutonomousForkliftExample` | `AgentResource` with on/off-shift + reactive statechart |
| `JobShopExample` | multiple resource agents with statechart-driven dispatch |
| `DroneDeliveryExample` | 3D capstone — `ContinuousVolume` + `Dynamics3D` + drone fleet |

**KSL Book**

For background on replications, warmup, output analysis, and
variance reduction, see [the KSL Book](https://rossetti.github.io/KSLBook/).
