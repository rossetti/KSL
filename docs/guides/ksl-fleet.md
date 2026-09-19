# Using `ksl.modeling.fleet`

*Experimental.* A fleet that decides for itself, tasked by a dispatcher
that has a process of its own and is allowed to take simulated time
deciding.

**Two packages, and the split is the point.** `ksl.modeling.fleet` is the
fleet: the dispatcher, tasks, tours, stops, lines, the manifest, the
policies, and every statistic. It names no substrate.
`ksl.modeling.agv` is one *binding* of it — `AgvSystem` and `AgvVehicle`,
which run the fleet over a guide path and add the rows only a guide path
can fill (zones, blocking, deadlocks). `FreePathFleet` and
`FreePathVehicle`, in the fleet package itself, are the other: the same
dispatcher and the same tours over a spatial model, where vehicles travel
directly and never wait for one another.

Everything in this guide is the fleet layer unless it says otherwise, and
works the same on either.

New to KSL's vehicles? [`ksl-transport`](ksl-transport.md) maps the four
transport subsystems onto two questions — does space push back, and who
decides — and says which to use.

Code snippets here are compile-verified against the source on every build
(`KSLCore/src/test/kotlin/ksl/modeling/fleet/doc/AgvGuideSnippets.kt`).

---

## 1. What this package is for

Use this package when **who decides, and when, is part of the answer.**

This is the second of two subsystems over the same physical world. The
first, [`ksl-guidedpath`](ksl-guidedpath.md), gives you vehicles on a
network of zones that block each other; there, an entity holds the
protocol — it asks a pool for a cart, waits, rides, hands it back. The
network, the zones, the routing, the blocking and the deadlock detection
in this package are *literally the same code*. What changes is where the
decision lives.

Compare the one line in an entity's process:

```kotlin
guidedTransport(carts, destination = EXIT, pickupLocation = ENTRY)   // passive
transportByFleet(agv,    destination = EXIT, origin = ENTRY)           // active
```

They look alike and mean something quite different. Under the passive
paradigm the choice of *which* cart is made inside the entity's own
process, at the instant it happens to ask, over whichever carts happen to
be free at that instant. There is nowhere else it could be made, because
no other object is running. Under the active paradigm a **dispatcher**
decides: it can see the whole fleet and the whole board, and it may
consume simulated time doing so.

That last clause is the package. Five things become expressible that a
pool's allocation rule cannot express at all — not because they are hard,
but because there is nowhere to put them:

- **Batching.** Wait ten minutes, then allocate over everything that
  accumulated. Under the passive paradigm this would mean making the
  asking entity wait for reasons that have nothing to do with it.
- **Negotiation.** Broadcast a call for proposals, let each vehicle answer
  from what *it* knows about itself, award the best bid — and charge the
  model for the deadline. A passive resource has nothing with which to
  hold an opinion.
- **Re-tasking in flight.** Take a task back from a vehicle three-quarters
  of the way to a far pickup when a nearer one appears. The movement
  machinery turns a vehicle round; the dispatcher is the object whose
  business it is to decide when that should happen.
- **Consolidation.** Fill a vehicle. A pool hands one cart to one entity,
  so a vehicle with room for four carries one; deciding which loads ride
  together, and in what order the vehicle collects and drops them, is a
  decision about the fleet rather than about any one load
  ([§4](#carry-more-than-one-load-at-a-time)).
- **Fixed routes.** Run a service that calls at stops on a cycle and
  carries whoever is waiting, rather than one that is summoned
  ([§4](#run-a-fixed-route--a-bus-line-a-milk-run-a-line-haul)).

**When not to use it.** If your rule is "send the nearest free cart" and
you are content for it to be evaluated the moment an entity asks, the
passive subsystem is simpler, has fewer moving parts, and gives the same
answer. With one vehicle the two agree *exactly*, to the digit — which is
the result that makes them two models of one world rather than two worlds
(`ksl.examples.general.agv.TwoParadigmsExample`).

**Not modelled in this version.** Everything the physical layer does not
model ([`ksl-guidedpath` §1](ksl-guidedpath.md#1-what-this-package-is-for):
acceleration, turn penalties) is equally absent here.

---

## 2. The mental model

**Three objects, and the modeller names two of them.**

- **`FleetSystem`** — the fleet and its dispatcher. It is an `AgentModel`
  (and so a `ProcessModel`), because the vehicles and the dispatcher are
  agents with processes and mailboxes. Your own `ProcessModel` holds one
  as a child element, and your entities suspend in its queues. It is
  abstract, so what you actually name is one of its bindings:
  `AgvSystem` for a guide path, `FreePathFleet` for a spatial model.
- **`FleetVehicle`** — the permanent identity you declare, name, and read
  statistics from. It is *not* the thing that decides and *not* the thing
  that occupies space. It **composes** a body for its physical presence,
  and holds a per-replication agent for its behaviour. Again you name a
  binding: `AgvVehicle`, or `FreePathVehicle`.
- **`Dispatcher`** — decides who goes where, and owns the line of work
  waiting to be done. One class, whatever the vehicles run on.

A vehicle composes its body rather than inheriting it, and the reason is a
modelling stance rather than a taste in inheritance: on a guide path that
body is a `GuidedTransporter`, and a `GuidedTransporter` is a `Resource` —
passive by construction. Inheriting one would expose a way to `seize` this
vehicle as though it were a tool, which is exactly what this subsystem
exists to replace. Composition is also what makes the substrate
replaceable: the body is the only part that a second one has to change.

**A task is what queues, not a load.** When an entity asks for transport,
a `Task` is created and posted to the dispatcher's `TaskQ`. The task is a
`QObject`, so it carries the waiting statistics; the entity itself
suspends in a hold queue that reports nothing. This is deliberate and is
the same separation `Conveyor` makes: a hold queue is how a suspended
entity is found again, and letting it double as the statistic conflates a
mechanism with a measurement. It also gives an assignment policy a
first-class object to rank and a bidding policy something to bid on.

**A vehicle is available because it said so.** The dispatcher never infers
availability; a vehicle declares it. A policy that names a vehicle the
dispatcher did not offer it raises `FleetDispatchException` rather than
being quietly skipped.

**A policy decides only.** It cannot move a vehicle, claim a zone, post a
task, or change the board. The board it is handed is read-only and an
`AssignmentProposal` is inert, so this is enforced by the types rather
than by a rule you must remember. It may read anything.

**Once a load is aboard, the delivery finishes.** Re-tasking is supported
right up to the instant of pickup and not past it. Revoking an assignment
whose load is aboard raises `FleetAssignmentException`.

---

## 3. Quick start

The same one-way loop the passive guide uses: a spur down to the exit, a
parking spur for the cart, and parts carried from entry to exit.

```kotlin
val network = GuidedPathNetwork.builder("ShopFloor")
    .intersection("I1", x = 0.0, y = 72.0)
    .intersection("I2", x = 48.0, y = 72.0)
    .intersection("I3", x = 48.0, y = 0.0)
    .intersection("I4", x = 0.0, y = 0.0)
    .intersection("I5", x = 0.0, y = -36.0)
    .intersection("I6", x = 54.0, y = 72.0)
    // A one-way loop, so two vehicles cannot meet head-on.
    .link("Link1", "I1", "I2", length = 48.0, zoneLength = 12.0)
    .link("Link2", "I2", "I3", length = 72.0, zoneLength = 12.0)
    .link("Link3", "I3", "I4", length = 48.0, zoneLength = 12.0)
    .link("Link4", "I4", "I1", length = 72.0, zoneLength = 12.0)
    .link("ExitSpur", "I4", "I5", length = 36.0, zoneLength = 12.0, type = LinkType.SPUR)
    // A parking spur per vehicle, so an idle one is out of the traffic.
    .link("DepotSpur", "I2", "I6", length = 6.0, zoneLength = 6.0, type = LinkType.SPUR)
    .station(ENTRY, "I1")
    .station(EXIT, "I5")
    .station(DEPOT, "I6")
    .build()
```

Then the model. Note `spatialModel = network`: the parts travel on the
guide path, so it is their spatial model too.

```kotlin
class AgvShop(parent: ModelElement) : ProcessModel(parent, "AgvShop") {

    val network = buildNetwork()

    init {
        spatialModel = network
    }

    // The fleet and its dispatcher. A child of this model; its entities suspend in its queues.
    val agv = AgvSystem(this, network, name = "Agv")

    val cart = AgvVehicle(
        agv, TransporterPlacement.At(DEPOT), ConstantRV(10.0), name = "Cart"
    ).apply { homeBase = DEPOT }

    val timeInSystem = Response(this, "TimeInSystem")
    val delivered = Counter(this, "Delivered")

    private val timeBetweenArrivals = ExponentialRV(40.0, 1)

    inner class Part : Entity() {
        val production = process(isDefaultProcess = true) {
            val arrived = time
            currentLocation = network.requireLocation(ENTRY)
            // States what it needs and suspends. It never chooses a vehicle.
            transportByFleet(agv, destination = EXIT, origin = ENTRY)
            timeInSystem.value = time - arrived
            delivered.increment()
        }
    }

    inner class Source : Entity() {
        val arrivals = process(isDefaultProcess = true) {
            repeat(400) {
                delay(timeBetweenArrivals)
                activate(Part().production)
            }
        }
    }

    override fun initialize() {
        activate(Source().arrivals)
    }
}
```

There is no `GuidedPathTransportSystem` in that model, and no pool. The
`AgvSystem` builds and owns the space layer itself -- a
`GuidedPathSpace`, which is the zones, the movement engine and the
congestion statistics without either paradigm's protocol. Both
subsystems run on one, and the statistics `AgvSystem` delegates are that
layer's.

---

## 4. How do I…?

### …ask for transport without waiting for it?

`transportByFleet` is the composed verb. When the process must act between
asking and being carried — so the vehicle can be on its way while an
operation finishes — use the two it is built from:

```kotlin
val task = requestFleetTransport(agv, destination = EXIT, origin = ENTRY)
delay(5.0)                       // finish the operation, release the machine
val result = awaitFleetTransport(task)
```

The returned task **must** be awaited. Abandoning it leaves a vehicle to
collect an entity that never suspends.

### …find out what a transport cost?

Both verbs return a `FleetTransportResult`:

```kotlin
val waited = result.waitForAssignment
val fetched = result.waitForArrival
val rode = result.timeAboard
val who = result.vehicleName
val turnedRound = result.numReassignments
```

`waitForAssignment` and `waitForArrival` are the pair the passive
subsystem cannot report at all: nothing there holds a *commitment*, so
there is no instant at which a decision was made to measure from. Here a
dispatcher decides at one instant and a vehicle arrives at another, and
the two sum to exactly the task's time in the dispatcher's queue.

For the fleet rather than one load, the same five figures the passive
subsystem publishes are on `AgvSystem`, delegated to the guide path
underneath because that is the layer both paradigms run on:

```kotlin
val approach = agv.approachTime.withinReplicationStatistic.weightedAverage
val ride = agv.rideTime.withinReplicationStatistic.weightedAverage
val stuck = agv.transportBlockedTime.withinReplicationStatistic.weightedAverage
val zones = agv.zonesTraversedPerTransport.withinReplicationStatistic.weightedAverage
val far = agv.routeLengthPerTransport.withinReplicationStatistic.weightedAverage
```

Note where the boundaries fall, because they are not the same as the
result's. `approachTime` runs from the instant a vehicle was committed
to the load until it reaches it, and `rideTime` from there until it is
set down -- each stopping short of the loading or unloading delay that
follows. `waitForArrival` and `timeAboard` on the result are the wider
intervals that include those delays, which is why neither pair is
derivable from the other. Measured this way the two paradigms report the
same numbers for the same shop, which `PerCarryStatisticsTest` holds
them to.

These two are **protocol intervals, not vehicle states**, and the
distinction matters as soon as a vehicle carries more than one load
([below](#carry-more-than-one-load-at-a-time)). An approach includes time
the vehicle spent blocked, and time disengaging from a repositioning
move, neither of which is moving empty.
For the state question, ask the vehicle:
`Cart:Body:FracTimeMovingEmpty` is the fraction of its time moving with
no load, and `FracTimeTransporting` the fraction moving with one. Those
are the figures that stay true whatever a vehicle's capacity.

One consequence worth knowing: after a re-tasking, `approachTime` runs
from the **last** assignment, not the first. The abandoned approach is
not empty travel on this load's behalf, and `numReassignments` on the
result is what says it happened.

### …compare a passive model with an active one?

Put both in one model and the reports sit side by side, but three
differences in how rows are named will meet you.


| Passive row | Active row | Why |
|---|---|---|
| `Sys:NumZoneTraversals` | `Agv:Space:NumZoneTraversals` | One segment, applied to every shared row |
| `Cart1:FracTimeBlocked` | `Cart1:Body:FracTimeBlocked` | One segment, applied to every physical row |
| `Sys:TransportTime` | *(no counterpart)* | Different intervals — see below |

**The `:Space:` segment** appears because a passive transport system
*is* a guide path space, so its space rows sit at its own level, while
an active system *has* one, so they sit under it. The mapping is one
segment applied mechanically, which `StatisticNamingTest` asserts over
the whole set rather than over a hand-kept list.

**The `:Body` segment** is the same idea one level down: an `AgvVehicle`
composes a `GuidedTransporter` that carries the physical statistics, and
model element names are unique, so the body cannot share the vehicle's
name. `StatisticParityTest` asserts that mapping.

**`TransportTime` has no active counterpart, and this is the one to be
careful about.** The passive row runs from the entity's request to it
being set down — the whole story, including the wait for a cart. The
active subsystem decomposes that wait deliberately, so what corresponds
to it is a sum, not a row:


```kotlin
// Sys:TransportTime  ==  WaitForAssignment + (waiting for arrival) + TimeAboard
val whole = agv.dispatcher.waitForAssignment.withinReplicationStatistic.weightedAverage +
        agv.timeAboard.withinReplicationStatistic.weightedAverage
// ... or read it per load, where the result gives you the total directly:
val total = result.totalTime
```

The active row that measures aboard-to-set-down is called `TimeAboard`
rather than `TransportTime` for exactly this reason. The two subsystems'
own row names are kept disjoint, so a study that lines rows up by name
never compares two different intervals.

One last pair worth reading carefully: an active model reports both
`Agv:Space:NumTransportersIdle` and `Agv:NumVehiclesIdle`, and they
answer different questions. The first counts vehicles **standing
still**; the second counts vehicles **carrying no task**. A vehicle
repositioning to its home base satisfies the second and not the first.
Each row uses the word of the layer that owns it — transporter for the
shared space, vehicle for this subsystem — because renaming either would
make the shared layer speak one consumer's dialect.
### …change the dispatching rule?

```kotlin
val agv = AgvSystem(parent, network, assignmentPolicy = LeastUsedVehiclePolicy())
// Or later, while the model is not running:
agv.dispatcher.assignmentPolicy = NearestVehiclePolicy()
// What order the policy sees the waiting tasks in:
agv.dispatcher.taskSelectionRule = ByPriorityTaskSelection()
```

Nine ship, and six of them answer immediately. `NearestVehiclePolicy` is
the default and is what most people mean by "send the closest cart" — measured **along the guide path**, never
straight-line, because on a one-way loop a vehicle a few feet past the
pickup has to go all the way round. `FurthestVehiclePolicy` is
deliberately poor and exists so that "nearest is better" can be a finding
rather than an assertion. `LeastUsedVehiclePolicy` balances wear instead
of travel, and the two genuinely conflict. `PullFromBoardPolicy` is the
degenerate case — vehicles taking the next job off a shared queue —
expressed as a policy rather than as an architecture, which is what makes
it the policy the equivalence benchmark uses. `RandomAssignmentPolicy`
takes a model-owned stream. `ScoringAssignmentPolicy` is below. The other
three — batching, negotiation, and re-tasking — consume simulated time or
take work back, and have recipes of their own below.

The selection rule orders what the policy *sees*: `FifoTaskSelection`,
`ByPriorityTaskSelection`, `ByAgeTaskSelection`. It is a separate seam
from the policy because "which task next" and "which vehicle for it" are
separate questions.

**To vary the policy from a scenario rather than in code**, set it by name:

```kotlin
runner.addScenario(model, name = "LeastUsed",
    inputs = mapOf("Agv:Dispatcher.assignmentPolicyName" to "LeastUsedVehicle"))
```

`assignmentPolicyName` is a `@KSLStringControl`, and it offers five of the
nine: `PullFromBoard`, `NearestVehicle`, `FurthestVehicle`,
`LeastUsedVehicle`, `Consolidating`. **A policy has a name only when a name
is all it takes to define it.** The other four take a window, a deadline, a
threshold, a stream or a scoring function, and those arguments are not
tunings of a policy — they *are* the policy. A thirty-second batching window
and a five-minute one are different dispatching rules that share an
implementation, so a name standing for one of them would let a study vary
the label while freezing the number, then report the result as a comparison
of rules.

So the parameterised four are set by assigning the object, and reading the
name reports what is in force either way:

```kotlin
agv.dispatcher.assignmentPolicy = BatchedAssignmentPolicy(window = 30.0)
println(agv.dispatcher.assignmentPolicyName)   // BatchedAssignmentPolicy(window=30.0)
```

A study over a policy's *parameter* is a study over a number, and belongs in
the model that chooses it — which is what `DispatchingRuleComparison` does,
naming its own design points `BatchedWindow30` and `ContractNetDeadline5`.

### …wait and decide over a batch?

```kotlin
AgvSystem(
    parent, network,
    assignmentPolicy = BatchedAssignmentPolicy(window = 10.0, inner = NearestVehiclePolicy()),
    name = "Agv"
)
```

This is the policy the interface exists for. Every rule above answers
immediately and could have been a function; this one consumes simulated
time, and while it waits the board keeps filling. A load arriving just
after a window opens waits the whole of it, and in exchange the fleet is
allocated over a *set* of tasks rather than one at a time in arrival
order. Whether that pays depends on the layout and the load — which is
exactly why it is something to measure rather than a behaviour built into
the dispatcher.

The window runs from when the dispatcher wakes, so an idle fleet still
pays it.

### …let the vehicles bid?

```kotlin
AgvSystem(
    parent, network,
    assignmentPolicy = ContractNetAssignmentPolicy(deadline = 0.5),
    name = "Agv"
)
// What each vehicle offers is its own business, and may differ across the fleet.
for (vehicle in fleet) {
    vehicle.bidPolicy = DeclineWhenBusyBid(CompletionTimeBid())
}
```

A real Contract-Net negotiation, not a distance rule in an auction's
clothes: the dispatcher broadcasts, each vehicle answers from its own
`BidPolicyIfc`, and two fleets with the same layout and different bidding
rules reach different awards. Declining is *silence* rather than a
message. **The deadline consumes simulated time**, which is the point —
negotiation is not free, and a model that charges for it puts the cost in
the loads' waiting time instead of in an assumption.

A deadline of zero is well defined and is not a trap. `bid` is not a
suspending function, so every vehicle has answered inside the broadcast
itself; a bidding rule that consumed simulated time could not be written.

```kotlin
class LeastLoadedBid : BidPolicyIfc {
    override fun bid(
        vehicle: FleetVehicle,
        cfp: CallForProposals,
        space: FleetSpaceIfc
    ): Bid? = Bid(vehicle, vehicle.numTasksCompleted.value, note = "tasks done so far")
}
```

Ties are broken by **vehicle name**, not by declaration order. Everywhere
else in this subsystem declaration order is the tiebreaker; in an auction
it would be wrong, because bidders are symmetric except for what they
offer.

### …take a task back from a vehicle?

```kotlin
AgvSystem(
    parent, network,
    // A swap must save more than 50 units of guide path before it is worth making.
    assignmentPolicy = ReassigningPolicy(improvementThreshold = 50.0),
    name = "Agv"
)
```

The threshold is what makes this usable rather than pathological. Without
one, any improvement at all justifies a swap and a loaded fleet churns:
revoke, redirect, revoke again as the board shifts under it. Set it in the
same units as your link lengths.

Two different swaps are tested, and a fleet of one has only the second:
somebody else could collect this load sooner, or *this* vehicle could
collect a different load sooner.

**The inner policy must rank pairings, not tasks.** The default is a
scoring policy over the feasible set, and that is not arbitrary.
`NearestVehiclePolicy` walks the tasks and picks a vehicle for each, so
with one vehicle and two tasks it hands the first task in the queue
whatever is free — including the vehicle just taken off it. Wrapping a
re-tasking policy around a rule like that revokes and immediately
re-awards the same pairing, and accomplishes nothing but a rising
revocation count. The failure is silent: the model runs, the loads are
delivered, and only `numAssignmentsRevoked` says anything is wrong.

### …write my own policy?

One method, and it may suspend:

```kotlin
class AlphabeticalPolicy : AssignmentPolicyIfc {
    override suspend fun KSLProcessBuilder.assign(
        context: DispatchContext
    ): List<AssignmentProposal> {
        val free = context.available.sortedBy { it.name }.toMutableList()
        val proposals = mutableListOf<AssignmentProposal>()
        for (task in context.board.unassigned) {
            if (free.isEmpty()) break
            proposals.add(AssignmentProposal(free.removeAt(0), task))
        }
        return proposals
    }
}
```

`assign` is written as an extension **on the process builder**, which is
forced: `KSLProcessBuilder` is `@RestrictsSuspension`, so a plain
`suspend fun assign(context)` would not compile at the one call site that
matters. It buys something too — your implementation receives the real
process builder, so it may `delay` for a window, `hold`, or run an
auction, rather than being confined to whatever a context object thought
to expose.

Draw any randomness from a model-owned stream. A policy that consulted
wall-clock time or unmanaged global state would make a run irreproducible
in a way no test would catch.

`context.feasible` is the vehicle-to-task pairings available at this
instant, as an object to enumerate and search rather than a predicate to
apply after guessing — the shape a cost-function or value-function policy
has, and the shape a decision epoch has:

```kotlin
ScoringAssignmentPolicy { proposal, feasible ->
    val travel = feasible.cost(proposal.vehicle, proposal.task)
    // Lower is better, so a task declaring a lower priority number is worth going further for.
    travel + 100.0 * proposal.task.priority
}
```

Feasibility here is **reachability and nothing more**. Enough charge, the
right attachment, a shift that has begun — those are judgements about
desirability that vary by model, and they belong in a bidding rule or a
scoring function. Reachability is a fact about the network.

### …decide where an idle vehicle goes?

Per vehicle, so a fleet may be heterogeneous:

```kotlin
fleet[0].dispositionPolicy = ReturnToHomeBaseDisposition()
fleet[1].dispositionPolicy = MoveToStagingDisposition("StagingSpur2")
```

```kotlin
class GoHomeWhenTiredDisposition(private val after: Double) : DispositionPolicyIfc {
    override fun disposition(vehicle: FleetVehicle): Disposition =
        if (vehicle.numTasksCompleted.value >= after) Disposition.ReturnToHomeBase
        else Disposition.ParkInPlace
}
```

A disposition policy is consulted **only after** the dispatcher has been
given the chance to assign and has declined, so no disposition can cause a
vehicle to idle while work waits. That is structural — the branch is
unreachable until the dispatcher has passed — rather than a rule an
implementer could break.

See §6 before choosing `ParkInPlaceDisposition`.

### …model batteries and charging?

Give the vehicle a `Battery`, tell the system where the chargers are, and
set the two policies that keep it charged.

```kotlin
val agv = AgvSystem(
    this, network, name = "Agv",
    assignmentPolicy = ChargeReservePolicy(NearestVehiclePolicy())
)
agv.addCharger("ChargeSpur")

val cart = AgvVehicle(
    agv, TransporterPlacement.At("I6"), ConstantRV(3.0), name = "Cart",
    battery = Battery(
        capacity = 1000.0,
        chargePerDistance = 0.5,   // traction: drawn per foot travelled
        chargePerTime = 0.02,      // hotel load: drawn always, parked included
        chargingRate = 100.0
    )
).apply {
    dispositionPolicy = ChargeWhenLowDisposition(threshold = 0.6)
}
```

Then read `cart.stateOfCharge`, `cart.fractionCharged`, and on the report
`Cart:FracTimeCharging`, `Cart:NumChargingSessions`,
`Cart:MinStateOfCharge` and `Cart:NumTimesStranded`. Those four rows exist
only for a vehicle that has a battery — a row measuring something the model
does not have is a question its reader has to answer every time.

**Two drain rates, because a real vehicle has two.** Traction energy scales
with distance and stops when the vehicle stops. Hotel load — controller,
radio, lights, heating — scales with time and does not. `chargePerTime`
defaults to zero, so a model that ignores idle draw is exactly a model with
one rate.

**Charge is derived, not stepped.** Nothing schedules an event for it: the
level is a closed-form function of the vehicle's two odometers,
`distanceTravelled` and elapsed time, computed whenever you ask. Adding a
battery to a model does not change how many events its run takes, which
`BatteryTest` asserts by running the same model both ways.

**Exhaustion is noticed at the next zone boundary.** A vehicle cannot stop
part way into a zone — it is physically between two places and has already
claimed the space ahead — so a flat vehicle completes the entry it is
committed to and halts on the zone it holds. From then on it stands there,
and every route through those zones is closed.

**Both policies, or neither works.** They do different jobs and the fleet
needs both:

| Policy | What it does | Why it is not enough alone |
|---|---|---|
| `ChargeReservePolicy` | Refuses an assignment the vehicle could not finish and still reach a charger | Stops the vehicle stranding, but never sends it to charge — so it stops working |
| `ChargeWhenLowDisposition` | Sends an idle low vehicle to a charger | Dispositions are consulted only when the dispatcher has no work, and a busy fleet always has work |

A saturated fleet with only the disposition never charges at all, and runs
flat exactly as though it had no charging policy. The reserve is what makes
a low vehicle decline the next load, and declining is what makes it idle
enough for its disposition to be asked.

### …make vehicles break down?

Give the vehicle a `FailureModel`. A failure is due once the chosen
quantity has advanced by a draw since the last repair, and the quantity is
the choice you are making:

```kotlin
val cart = AgvVehicle(
    agv, TransporterPlacement.At("I6"), ConstantRV(3.0), name = "Cart",
    failureModel = FailureModel.clockBased(
        timeBetweenFailures = ExponentialRV(400.0, streamNum = 7),
        repairTime = LognormalRV(20.0, 25.0, streamNum = 8),
        basis = FailureBasis.OPERATING_TIME
    )
)
```

| Factory | Basis | Failures go with |
|---|---|---|
| `FailureModel.clockBased(..., OPERATING_TIME)` | Time the vehicle was not idle | Hours in service |
| `FailureModel.clockBased(..., CALENDAR_TIME)` | Elapsed simulated time | Age |
| `FailureModel.usageBased(...)` | Tasks completed | Duty cycles |
| `FailureModel.distanceBased(...)` | Distance travelled | Mileage |

`Cart:NumFailures`, `Cart:FracTimeFailed` and `Cart:TimeOutOfService` land
on the report, for a vehicle that has a failure model and only for one; and
`Agv:FailedTimePerTransport` appears once any vehicle in the fleet can fail.

`TimeOutOfService` is the **whole procedure** — the wait for a technician,
the walk, the assessment and any tow, not the repair alone. What the repair
itself costs is the `repairTime` you gave the failure model.

**A failure interrupts the tour; it does not revoke the assignment.** The
vehicle keeps its load, is repaired, and resumes the tour from the stop it
had reached — from wherever it now stands, if somebody moved it. Handing the
task back would put a load on the board while a vehicle was still physically
holding it, and two vehicles would then believe they had it.

**What happens next is a policy, not a duration.** See *…do what a site
actually does when a vehicle breaks down?* below. The default repairs the
vehicle where it stands for the drawn repair time and nothing else.

**A failure is noticed at the next check point, not at the instant it comes
due.** None of the four bases has events of its own, so a failure accrues
silently and fires at whichever comes first: the next zone boundary, or the
end of the current tour. A vehicle parked with nothing to do therefore does
not fail while parked — it fails at the first boundary of its next journey,
carrying whatever came due while it stood there. For a busy fleet this is a
rounding difference. For a mostly idle one it is not, and `CALENDAR_TIME`
on such a fleet reads as *failures that had become due by the time the
vehicle next worked*.

**Calendar and operating time are not a refinement of one another.** On a
fleet that is idle most of the run they give different counts, which is why
both are offered rather than one being chosen for you.

The gap is smaller than the idle fraction alone would suggest, and it is
worth knowing why: **failures do not queue up**. When one fires, the next
threshold is drawn from the basis value at that instant, so several
failures that came due while the vehicle stood still collapse into one.
A calendar-basis fleet therefore fails roughly as often as it is *checked*,
not as often as the clock says it should — which reads as "the vehicle was
found broken when it was next needed". One cart, arrivals averaging 600
apart over a horizon of 6000, a failure every 120: 26 failures on calendar
time against 21 on operating time, with the cart working 2568 of the 6000.

A vehicle under repair on the guide path is an obstruction for as long as
the repair lasts — see §6 — and one still under repair when the horizon
falls shows on `Agv:NumVehiclesFailedAtHorizon`, which is what says the
open assignment and the suspended entity beside it belong to a breakdown
rather than to a run that was too short.

### …do what a site actually does when a vehicle breaks down?

Nobody's AGV is repaired by a number. Somebody is told, somebody who is free
walks over, looks at it, and pushes it out of the aisle if it is in the way.
Every step of that is a wait or a branch, so what happens after a vehicle
stops is a **process**, written as an `InterruptionPolicyIfc`:

```kotlin
class VisitAndAssess(
    private val technicians: ResourceWithQ,
    private val refuge: String,
    private val reportingDelay: RVariableIfc,
    private val walkingTime: RVariableIfc,
    private val assessmentTime: RVariableIfc
) : InterruptionPolicyIfc {

    override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
        val vehicle = interruption.vehicle
        delay(reportingDelay)                        // nobody noticed for a while
        val tech = seize(technicians)                // somebody has to be free
        delay(walkingTime)                           // and walk to it
        delay(assessmentTime)                        // and look at it
        if (interruption.isObstructingNow) {            // decided at the vehicle, not before
            tow(vehicle, refuge, atVelocity = 1.0)   // pushed out of the aisle
        }
        if (interruption is Interruption.Failed) delay(interruption.repairTime)
        release(tech)
    }
}

cart.interruptionPolicy = VisitAndAssess(
    technicians, refuge = "MaintenanceSpur",
    reportingDelay = ConstantRV(2.0),
    walkingTime = ExponentialRV(8.0, streamNum = 9),
    assessmentTime = ConstantRV(5.0)
)
```

`VisitAndAssessPolicy` ships with exactly that shape, so most models can use
it as it stands.

The framework contributes two verbs and one context object; everything else
is ordinary process code.

| | |
|---|---|
| `tow(vehicle, to, atVelocity)` | Pushes a stopped vehicle along the guide path to somewhere it is out of the way, and waits for it to get there |
| `charge(vehicle)` | Holds a vehicle on a charger until its battery is full |
| `Interruption` | Where it stopped, what it holds, what it was doing, the task in hand, and **who is stuck behind it** |

**A towed vehicle is still on the guide path.** On `AgvSystem` it claims
and gives up zones exactly as a driving one does, because a vehicle being
pushed down an aisle blocks that aisle every bit as much as one driving
down it. What changes is where it ends up. It is exempt from its own faults while under tow —
neither a flat battery nor the failure it is being pushed away from stops it
part way.

**The velocity is the pusher's**, and overrides the vehicle's own
distribution for the duration. How fast a person moves a dead AGV has
nothing to do with how fast it drives.

**The policy has no return value.** When it returns, the subsystem asks the
same question the vehicle asks at every zone boundary: *is this fit to carry
on?* A repair that finished leaves a repaired vehicle and it re-routes from
wherever it now stands. A policy that did nothing about a flat battery
leaves a flat vehicle, and it goes out of service for the rest of the
replication. There is no way for a policy to claim it fixed something it did
not.

**Two questions, and they are answerable at different moments.**

| | Asks | True when |
|---|---|---|
| `isOnAThroughRoute` | is this a bad place to stop? | any zone the vehicle holds is one traffic passes through — from the first instant |
| `isObstructingNow` | is anybody actually stuck? | some vehicle is waiting on a zone it holds — only once time has passed |

`isObstructingNow` is a live query, and a queue behind a stopped vehicle
takes time to form, so asking at the instant it stops nearly always says no.
Measured on the chapter's shop over sixteen breakdowns: asking immediately
found an obstruction *not once*, while asking forty time units later found
several — on a layout where leaving the vehicle in the aisle cost the
healthy cart 77% of its time blocked. Nothing errors; the policy just never
tows.

`isOnAThroughRoute` is a fact about the **layout** rather than about who
happens to be queued, so it is true straight away. It is false only when
every zone the vehicle holds is on a spur or at a dead end — which is what a
spur is for, and why the chapter's layout parks its carts on them.

A policy that decides before any time has passed wants the first. A policy
that has already spent time getting somebody to the vehicle can use either,
and the second is then the sharper question. `VisitAndAssessPolicy` tows
when either is true.

**What towing is worth**, on that same shop — two carts, one breaking down
every 150 feet with a 200-unit repair, over 4000 time units:

| | Loads delivered | Healthy cart blocked |
|---|---|---|
| Left in the aisle | 15 | 77% |
| Pushed onto a spur | 34 | 10% |

The same argument applies to a flat battery, and there it buys something no
other policy can: `tow` to a charger, `charge`, and the vehicle comes back.
Without that, a vehicle that runs flat is finished for the replication.

### …tell the rest of the model that a vehicle has broken down?

Attach a listener to the fleet. Any number may be attached, because
observing does not conflict — a maintenance log, an andon board and a
dispatcher all want to know and have nothing to do with one another:

```kotlin
agv.attachInterruptionListener(object : VehicleInterruptionListenerIfc {
    override fun stopped(interruption: Interruption) {
        breakdownLog.add(interruption.at to interruption.vehicle.name)
    }
})
```

Three moments, each with a do-nothing default so you name only the ones you
care about: `stopped`, then exactly one of `returnedToService` or
`outOfService`. Listeners are called synchronously from inside the vehicle's
own process, so they may read anything and may call
`dispatcher.reconsider()`, but they must not suspend and should not move
anything — deciding what happens to the vehicle is the policy's job.

Note the split: **one policy, because it decides; many listeners, because
they observe.** It is the same distinction the guide path makes between a
movement gate and an arrival listener.

**The dispatcher is not told automatically, and on a quiet fleet that costs
you the load.** A vehicle that stops keeps its assignment, declares nothing
and posts nothing, so nothing inside the subsystem wakes the dispatcher — and
a re-tasking rule that would have handed the work to a healthy vehicle is
never asked. Measured: one load, two carts, the nearer one breaking down
three feet out with a 2000-unit repair, and no other traffic to wake
anything.

| | Outcome |
|---|---|
| Dispatcher not told | the load is **never delivered** — still suspended at the horizon, with an idle cart on its spur |
| `ReconsiderOnInterruption` attached | delivered at t=136 by the other cart |

```kotlin
agv.attachInterruptionListener(ReconsiderOnInterruption(agv.dispatcher))
```

It is opt-in because waking on every breakdown adds an event, and adding one
to every model that has failures would change the order of things happening
at the same instant for a benefit only some of them can use.

**Being told is not the same as being able to act.** A rule that scores by
distance cannot see a breakdown at all: a vehicle that stopped part-way to
its pickup is no *further* away than it was, and usually nearer, so a
distance comparison never takes the work back however long it stands there.
`ReassigningPolicy` therefore treats a stopped incumbent as unable to
collect — any vehicle that can reach the pickup takes the task, and the
improvement threshold does not apply, because the incumbent's cost is no
longer a distance to compare against. A policy of your own should make the
same allowance; `FleetVehicle.isOutOfService` is the test.

**And a vehicle being dealt with is not spare capacity.**
`Agv:NumVehiclesOutOfService` is the third of the three fleet counts, and
the three partition the fleet at every instant. Without it a vehicle that
broke down between tours held no assignment and was therefore counted
*idle*, so a reader of `Agv:NumVehiclesIdle` saw capacity that was not
there.

### …send a vehicle somewhere without carrying anything?

Post an errand. It goes on the same board as transport requests and is
decided by the same policy, so it competes with them:

```kotlin
val errand = agv.dispatcher.postService("YardSpur")
```

**Any available vehicle may take it** — that is what makes it an errand
rather than something else. Charging is *not* one, and neither is repair: no
other vehicle can charge or repair this one on its behalf, which is why
those are a `Disposition` and an `InterruptionPolicyIfc` respectively. If
only one particular vehicle can do the job, it does not belong here.

**Nothing is suspended on it**, and three things follow:

- It can be cancelled, unlike a transport request — `dispatcher.cancel(errand)`.
  "You were going to park, but work has arrived" is safe, because cancelling
  strands nobody.
- The vehicle stays re-taskable for the *whole* errand, not just until it
  arrives. There is no load to take possession of, so nothing makes the
  assignment irrevocable.
- It contributes nothing to `Agv:Dispatcher:WaitForAssignment`, which
  decomposes what a *load* waited for.

**It does count** towards `NumTasksPosted`, `NumTasksCompleted`, and the
carrying vehicle's own `NumTasksCompleted` — so an errand is a duty cycle
like any other, which is what `LeastUsedVehiclePolicy` and a
`TASKS_COMPLETED` failure model should both see.

**One thing to weigh before using it.** An errand shares the dispatcher's
waiting line. `Agv:Dispatcher:TaskQ:TimeInQ` is documented as the wait for
transport, and that holds exactly while every task in the queue is a
transport request. Post errands and the row becomes the wait for *any* work
the fleet was asked to do. That is the honest reading of one queue serving
one fleet — the alternative, a second queue, would give a policy two boards
to allocate over — but it is a change to what a headline row means, so it is
worth being deliberate about.

### …abandon an outstanding request?

```kotlin
agv.dispatcher.cancel(task)
```

For a model that wants transport requests given up rather than left
hanging. It is not needed for teardown: `ProcessModel.afterReplication`
terminates every suspended entity without help from this subsystem.

### …ask what a vehicle is doing right now?

Everything the vehicle's body knows that is worth reading is on the vehicle:

```kotlin
val aboard = cart.manifest                  // a read-only view, in boarding order
val full = cart.isAtCapacity
val carrying = cart.isCarryingALoad
val speed = cart.currentVelocity            // now, not the mean of its distribution
val stuckFor = cart.cumulativeBlockedTime   // the running total behind FracTimeBlocked
val zones = cart.zonesEntered               // zero on a substrate with no zones
val beingPushed = cart.isUnderTow
val outOfService = cart.isOutOfService
```

**Move loads through the verbs, never through the manifest.** The list is a read-only view. Loads
get on and off through a stop action's `takeAboard` and `setDown` and through the transport
protocol, which is where every per-load interval this subsystem reports is recorded — a model that
moved a load any other way would run, and would be missing from every statistic.

Two things a body has that a vehicle deliberately does **not** forward: its `seizable` resource and
its `movementQueue`. Handing those out would let a model seize the vehicle as though it were a
tool, or suspend something in its movement queue, and replacing both of those is what this
subsystem exists to do.

---

### …find out where the congestion is?

Fleet-level rows say how much blocking there was. To find out *which aisles* produced it, switch on
one of the space layer's two finer tiers:

```kotlin
agv.collectLinkStatistics = true    // a response per link
agv.collectZoneStatistics = true    // a response per zone: the finest, and the most expensive
```

Both are off by default, because a thousand-zone network would otherwise put a thousand rows on
every report and in every output database. Both are settable up to the moment the model runs, in
either direction: switching one off takes its responses back out.

**Per-zone is the tier that answers questions about junctions.** A junction is a zone, so its
occupancy is the only direct measurement of what crossing traffic costs — and a junction of zero
length is still held for one traversal of the first zone beyond it, which is why an apparently
free crossing can be a bottleneck. See
[`ksl-guidedpath` §4](ksl-guidedpath.md#model-a-rectangular-grid-of-two-way-aisles) for the
arithmetic and for the layout lever that removes it.

---

### …check the subsystem's own bookkeeping?

```kotlin
agv.checkInvariants = true          // every clock advance; expensive, for development
agv.auditAtReplicationEnd = true    // once per replication; on by default
```

`checkInvariants` covers the guide path underneath as well, and reads the
same `ksl.guidedpath.checkInvariants` system property as the passive
subsystem, so switching checking on for a run switches it on for both
paradigms rather than for one of them. The closing audit reconciles
assignments against tasks, queued tasks against vehicles, suspended loads
against live tasks, and both conservation counts. A failure raises
`FleetInvariantViolation`, which — unlike the other three exceptions — names
something the *subsystem* got wrong rather than something a model did.

### …see what the horizon left undone?

```kotlin
val stranded = agv.numTasksNeverAssigned.acrossReplicationStatistic.average
val hanging = agv.numEntitiesNeverResumed.acrossReplicationStatistic.average
val open = agv.numAssignmentsStillOpen.acrossReplicationStatistic.average
```

These are `Response`s rather than `Counter`s, and the distinction is
semantic. A counter holds a running total that means something only while
a replication runs. These are a *single observation*, taken at the last
instant, of a quantity that does not exist until then: how much work was
left undone. They are written unconditionally, zero included — recording
only the bad replications would make the across-replication average a mean
over those, which is a number that looks like a fleet's performance and is
not.

---

### …run a fleet without a guide path?

Name a spatial model instead of a network. Nothing above the substrate changes.

```kotlin
class Yard(parent: ModelElement) : ProcessModel(parent, "Yard") {

    val plane = Euclidean2DPlane()

    // A fleet is written in named places; the spatial model supplies the geometry between them.
    val places = listOf(
        plane.Point(0.0, 0.0, "Depot"),
        plane.Point(300.0, 0.0, "Press"),
        plane.Point(0.0, 200.0, "Ship")
    )

    init {
        spatialModel = plane
    }

    val fleet = FreePathFleet(this, plane, places, name = "Yard")

    val cart = FreePathVehicle(
        fleet, "Depot", ConstantRV(30.0), name = "Cart", loadCapacity = 4, stepSize = 10.0
    ).apply { homeBase = "Depot" }

    inner class Pallet : Entity() {
        val movement = process(isDefaultProcess = true) {
            currentLocation = fleet.space.requireLocation("Press")
            transportByFleet(fleet, destination = "Ship", origin = "Press")
        }
    }
}
```

Those two declarations are the whole of the substrate. The dispatcher, the batching window, the
tour policy, the load capacity, stops, lines, stop control, batteries, breakdowns, the audit and
every statistic are the fleet layer, and each is written exactly as it is over a guide path.
Exchanging `FreePathFleet`/`FreePathVehicle` for `AgvSystem`/`AgvVehicle` and a network is all it
takes to run the same study on aisles that push back.

**Places are the spatial model's named locations.** A `DistancesModel` maintains them, so `places`
can be left out and defaults to `spatialModel.namedLocations`; a plane does not, so name its points
as above and pass them. Asking for a place the space does not have raises at once rather than
routing a vehicle nowhere.

**`stepSize` decides how quickly anything can interrupt a journey.** A free-path move is
interpolated, and a breakdown, a flat battery or a redirection is noticed at the next step. Smaller
is sooner and more events. On a spatial model that cannot say what lies between two places — a
`DistancesModel` is a table of pairwise distances and has no *between* — it is ignored, a journey is
one step, and a vehicle arrives before anything can stop it. That is a property of the space, not of
the vehicle, and it is worth knowing before you model a breakdown mid-trip.

**Nothing blocks, and the subsystem says so rather than staying quiet.** `FracTimeBlocked` is
registered on a free-path vehicle and reads exactly zero for the whole run. It is flat on purpose:
a free path's central assumption is that a vehicle never waits for another, and this is that
assumption appearing in the same row a guide-path run fills in. `isObstructingNow` answers no for
the same reason, so an interruption policy that tows only obstructing vehicles correctly never tows
here.

That assumption is also what makes free-path fleet sizing optimistic — see
[`ksl-guidedpath` §1](ksl-guidedpath.md#1-what-this-package-is-for) for the same haul measured both
ways, agreeing at one and two carts and parting company after that.

**A worked study.** `ksl.examples.general.fleet.FreePathFleetExample` runs two carts over a plane at
two capacities and two batching windows, and reports what each bought:

| | delivered | in system | assigned | blocked | loads/move |
|---|---|---|---|---|---|
| capacity 1, window 25 | 350.4 | 43.84 | 20.51 | 0.0000 | — |
| capacity 4, window 25 | 350.3 | 35.80 | 12.82 | 0.0000 | 1.332 |
| capacity 1, window 40 | 342.9 | 224.82 | 201.63 | 0.0000 | — |
| capacity 4, window 40 | 350.8 | 43.42 | 19.99 | 0.0000 | 1.581 |

**Read the throughput column first.** At window 25 the two capacities deliver the same load, so
the times beside them are comparable and carrying up to four cuts time in system by 18%. At window
40 the capacity-one fleet delivers 342.9 against 350.8 — it has fallen behind, so its 224.82 is a
number about the loads it managed rather than about the fleet, and comparing it with anything
compares two different questions. A batching window is a cost paid by every load and redeemed only
by capacity.

---

### …carry more than one load at a time?

Give the vehicle a `loadCapacity`, and give the dispatcher a way to hand it more than one task at
once:

```kotlin
// A vehicle only carries several if it is *given* several. A batching window collects the tasks;
// ConsolidatingPolicy is what fills a vehicle that still has room.
val agv = AgvSystem(this, network,
    assignmentPolicy = BatchedAssignmentPolicy(window = 5.0, inner = ConsolidatingPolicy()))

val cart = AgvVehicle(agv, TransporterPlacement.At("Depot"), ConstantRV(60.0),
                      name = "Cart", loadCapacity = 4)
```

The **order** the vehicle visits its stops in is a separate decision, and it belongs to the
dispatcher:

```kotlin
agv.dispatcher.tourPolicy = CheapestInsertionTourPolicy()   // the default
// or PickUpAllThenDeliverAllPolicy() -- a literal milk run
// or AppendTourPolicy()             -- the naive baseline, useful as a comparison
```

**Reading the results, and the one row that will mislead you.** `FracTimeTransporting` reads 1.0
whether a vehicle is carrying one load or four: it is a fraction of *time*, not of *capacity*. A
fleet moving one pallet at a time in a four-pallet body reports as fully utilised by that row. The
row that answers the question people read it as answering is **`CapacityUtilization`**.

| Row | Answers |
|---|---|
| `CapacityUtilization` | how much of the room was used, time-weighted |
| `FracTimeAtCapacity` | **is the capacity binding?** Mean utilization cannot say: a fleet at 50% could be alternately empty and full, which wants more vehicles, or steadily half full, which wants smaller ones |
| `NumLoadsAboard` | the mean number aboard |
| `LoadsPerLoadedMove` | **is consolidation happening?** A capacity-four fleet averaging 1.02 has the room and is not using it |
| `LoadsPerTour` | the same question at the round level, which is where a dispatching policy can be blamed for it |
| `FracTimeMovingEmpty` | the payoff: multi-load that does not reduce this bought nothing |

These are registered **only when `loadCapacity > 1`**. A row measuring something your model does not
have is a question its reader has to answer every time they meet it.

Read them in code off the vehicle, where they are null below a capacity of two — the same statement
the rows make by not being registered:

```kotlin
val used = cart.capacityUtilization?.acrossReplicationStatistic?.average
val full = cart.fracTimeAtCapacity?.acrossReplicationStatistic?.average
val perMove = cart.loadsPerLoadedMove?.acrossReplicationStatistic?.average
val aboard = cart.numLoadsAboardResponse?.acrossReplicationStatistic?.average
val perTour = cart.loadsPerTour?.acrossReplicationStatistic?.average
```

**The attribution rule, which you have to know before you sum anything.** When several loads ride
together and the vehicle is blocked for five units, **each load records five**. Per load that is
right — each of them waited five. Summed across loads it is fifteen against five units of vehicle
time.

> **Per-load rows are per-load. Do not add them up to get vehicle time.**

The same holds for `routeLength` and `timeAboard` in a `FleetTransportResult`. Questions about the
*vehicle* are answered by the vehicle's own time-weighted rows, which cannot double-count because
there is only ever one vehicle-second in a vehicle-second.

**What to expect from a study, and what not to.** Carrying more than one load lengthens the *ride* —
a load collected first waits aboard while the vehicle collects another — and shortens the *wait*,
because the vehicle gets round to everybody sooner. Which term wins is a property of how loaded your
fleet is, not a law: where the vehicle is the bottleneck the wait dominates and time in system falls,
and where it is not there is little to consolidate and little to gain. Capacity is worth most exactly
where the fleet is the constraint.

---

### …run a fixed route — a bus line, a milk run, a line-haul?

Declare the places loads wait, string them into a line, and post a cycle of it. Nothing is posted on
a rider's behalf and no vehicle is assigned to one: a rider stands at a stop, and whatever comes past
with room and somewhere useful to go takes it.

```kotlin
val depot = Stop(fleet, "Depot")
val cell1 = Stop(fleet, "Cell1")
val cell2 = Stop(fleet, "Cell2")

// A LineStop's default action is what serving a stop ordinarily means: put down everyone bound for
// here, then take whoever is waiting for somewhere further along.
val milkRun = Line("MilkRun", listOf(LineStop(depot), LineStop(cell1), LineStop(cell2)))

// One post is one cycle. A service that runs all day is a model that posts again --
// on a headway, on a timetable, or when the previous cycle ends.
fleet.dispatcher.postLine(milkRun)
```

and a load rides it:

```kotlin
val part = process {
    currentLocation = network.requireLocation("Cell1")
    val r = rideFrom(cell1, "Cell2")      // waits at the stop, boards, is set down
    timeToCross.value = r.totalTime
}
```

Use `requestRide` and `awaitRide` where the process has to do something between joining the line and
being carried, exactly as `requestFleetTransport`/`awaitFleetTransport` split the posted protocol.

**A vehicle takes you only if it is going where you are going**, read from its own remaining tour
rather than from any timetable. On a `cyclic` line every stop counts as ahead of it, because it comes
back round; on a one-way line only the stops it has not reached yet do. A destination that no service
reaches is therefore not an error and not a hang: the load stands at the stop and the stop reports
that it did.

**A rider keeps its seat across the cycle boundary.** That is what lets a circular service carry
somebody the long way round. A rider is put down early only when the vehicle's next round does not
go where the rider is going, or when the vehicle runs out of work altogether — a service withdrawn
under you is a real outcome, and the rider's process is what decides what to do about it.

**Writing your own action.** `TourStopActionIfc` is open and its `perform` suspends, so a stop that
dwells, meters boarding, or asks a question and waits for the answer is an ordinary implementation:

```kotlin
class BoardOnePerMinute(val stop: Stop) : TourStopActionIfc {
    override val servesStop = stop
    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        // Name the place before entering the context: inside `with(context)`, `stop` is the
        // context's own `TourStop` -- somewhere on this tour -- not the permanent `Stop`.
        val here = this@BoardOnePerMinute.stop
        with(context) {
            for (ride in here.waitingFor(onwardLocations)) {
                if (vehicle.spareCapacity <= 0) break
                delay(1.0)
                takeAboard(ride)
            }
        }
    }
}
```

> **Move loads with the context's verbs, never by hand.** `takeAboard` and `setDown` are where every
> per-load interval this subsystem reports is recorded. An action that puts something on a vehicle
> another way will run, and will be missing from every statistic.

**What a stop reports.**

| Row | Answers |
|---|---|
| `Q:NumInQ`, `Q:TimeInQ` | how many wait here and for how long — the wait for a *service*, not for a decision |
| `NumBoarded` / `NumAlighted` | the flow through this place |
| `NumPassedByFull` | **is the capacity binding?** visits at which a vehicle served the stop and left somebody standing |
| `NumPassedBySkipped` | visits a vehicle was instructed past. Kept apart from the row above because the remedies are opposite: more capacity for one, less expressing for the other |

and per rider, `TransitResult.numVehiclesPassed` is how many it watched go without room.

### …hold, run express, or turn a service short?

Give the vehicle a `stopControl`. It is asked **before each leg**, which is what makes `Skip` mean
*do not make this journey* rather than *drive all the way there and then refuse*:

```kotlin
// Hold at each stop until its scheduled departure, measured from the start of the cycle.
bus.stopControl = TimetableControl(mapOf(stopA to 0.0, stopB to 8.0, stopC to 17.0))

// Or let a controller decide, cycle by cycle.
bus.stopControl = DispatcherStopControl()
fleet.dispatcher.instruct(bus, StopInstruction.Skip)             // run it past the next stop
fleet.dispatcher.instruct(bus, StopInstruction.ServeAndEndTour)  // short-turn it
```

The instruction is collected at the vehicle's next ask, not applied the instant it is left: a
vehicle mid-leg is somewhere, and an instruction about nothing is not an instruction. Both
directions of initiative go through one seam — the vehicle asks, and `instruct` is how a controller
answers before being asked.

> **The control decides *where* — go, skip, or stop here. The action decides *what and how long*.**

An instruction that depends on how late the vehicle actually is cannot be computed before the leg.
That is not a gap: a stop action runs on arrival and may suspend, so express that as an action.

**Skipping a stop with somebody aboard bound for it is permitted, and counted.** Being carried past
your stop is one of the things a route study exists to measure. A control that will not do it can
see who is aboard, through `vehicle.ridersBoundFor(location)`, and decline. Skipping a stop that
serves a *posted task* is refused outright: there is a load suspended on it that nothing else would
ever set down.

The two costs are counted apart, because their remedies are opposite:

| Row | Where | Means |
|---|---|---|
| `NumPassedByFull` | the stop | a vehicle came and had no room — buy capacity |
| `NumPassedBySkipped` | the stop | a vehicle was instructed past — express less |
| `NumCarriedPast` | the vehicle | loads aboard bound for a stop it was told past |
| `NumStopsSkipped` | the vehicle | how much expressing this vehicle did |

**A transfer needs no machinery.** A rider whose process rides, then rides again, *is* a transfer
itinerary; the connection time is the second ride's `waitForVehicle`:

```kotlin
val shipment = process {
    val toHub = rideFrom(originStop, "Hub")
    val onward = rideFrom(hubStop, "Destination")
    connectionTime.value = onward.waitForVehicle
    numTransfers.value = 1.0
}
```

---

## 5. The key types at a glance

| Type | What it is |
|---|---|
| `FleetSystem` | The fleet and its dispatcher. An `AgentModel`. Substrate-independent; a subclass binds it to one. |
| `FleetVehicle` | The vehicle a modeller names. Composes a body; holds a per-replication agent. |
| `FleetVehicleCIfc` | Controlled access to one: the four policies it carries, what it is doing, and how its time was spent. Reports the same four fractions of time as `GuidedTransporterCIfc`, so the two paradigms' vehicles can be compared through their contracts and not only their implementations. A vehicle is *assigned*, never seized, so there is no capacity here and `fracTimeOnTask` takes utilization's place. |
| `AgvSystem` / `AgvVehicle` | The **guide-path binding** (`ksl.modeling.agv`): builds the space layer, adds the zone and blocking rows. Re-exposes the space's six diagnostic flags — `checkInvariants`, `auditAtReplicationEnd`, `deadlockDetectionEnabled`, `strictObstructionPolicy`, `collectLinkStatistics`, `collectZoneStatistics` — as controls under its own name, so an active model reaches them as `Agv.checkInvariants` rather than only through the inner element. |
| `FreePathFleet` / `FreePathVehicle` | The **free-path binding**: the same fleet over a spatial model, where nothing blocks. |
| `VehicleBodyIfc` | What the fleet needs from a vehicle's physical presence: a manifest, its time, something seizable, something that can be stopped. |
| `Dispatcher` | Decides who goes where; owns the `TaskQ`, which is the only queue this subsystem reports. |
| `Dispatcher.Task` | Something a vehicle may be asked to do. A `QObject`, so the *task* carries the wait. |
| `Dispatcher.TransportTask` | A load to collect and deliver. What `requestFleetTransport` returns. |
| `Dispatcher.ServiceTask` | A self-directed errand, by `ServiceKind` — currently `Reposition`. Posted with `postService`; cancellable. |
| `Dispatcher.LineTask` | One cycle of a declared service. Posted with `postLine`; cancellable. |
| `Tour` | The itinerary that discharges what a vehicle is committed to: stops in order, plus a cursor. |
| `TourStop` | Somewhere to be, and something to do there. Per-tour; names a location. |
| `TourStopActionIfc` | What a vehicle does on arriving. Open and **suspending**: a dwell, a boarding, a wait. |
| `StopContextIfc` | What an action is handed: the vehicle, the stop, the tour, and the verbs `takeAboard`, `setDown`, `holdAt`. |
| `Stop` | A permanent place where loads wait to board. Owns the second waiting line and reports it. |
| `Line` / `LineStop` | A declared service: a fixed sequence of stops, run cycle after cycle. Shared, never consumed. |
| `StopControlIfc` | Decides, stop by stop, whether a vehicle serves the next place. Asked **before the leg**; suspending. One per vehicle. |
| `StopInstruction` | `Serve(departNotBefore)`, `Skip`, `ServeAndEndTour` — hold, run express, turn short. |
| `TransitResult` | What a ride cost the load that took it. No `waitForAssignment` term: nobody decided. |
| `Battery` | A vehicle's energy store: capacity, two drain rates, and a charging rate. Immutable. |
| `FailureModel` | When a vehicle fails and how long a repair takes, against one of four bases. |
| `Interruption` | A vehicle has stopped: `Failed` or `OutOfCharge`, with where, what it holds, and who is stuck behind it. |
| `InterruptionPolicyIfc` | What happens next. A process, so it may wait for a technician, assess, and tow. One per vehicle. |
| `VehicleInterruptionListenerIfc` | Told when a vehicle stops and when it comes back. Any number per fleet. |
| `TaskBoard` | The read-only view of the queue handed to policies: `unassigned`, `assigned`, `oldest`. |
| `Assignment` | A vehicle's commitment to a task. `isRevocable` until the load is aboard. |
| `AssignmentProposal` | What a policy returns. Inert: proposing is not doing. |
| `FeasibleAssignments` | The pairings available now, enumerable and searchable. Feasible means reachable. |
| `FleetTransportResult` | What a transport cost, with the wait split into assignment and arrival. |
| `FleetDispatchException` | A policy named a vehicle that had not declared itself available. |
| `FleetAssignmentException` | An assignment was revoked after pickup, or used after completion. |
| `FleetProtocolException` | A task completed twice, or a suspended entity resumed twice. |
| `FleetInvariantViolation` | The closing audit found the subsystem's own account of itself does not add up. |

The seven replaceable policies: `AssignmentPolicyIfc` and `TourPolicyIfc`
(on the dispatcher), `TaskSelectionRuleIfc` (on its queue), and
`BidPolicyIfc`, `DispositionPolicyIfc`, `InterruptionPolicyIfc` and
`StopControlIfc` (per vehicle). A `LineStop` carries an eighth seam in its
`TourStopActionIfc`, and any number of `VehicleInterruptionListenerIfc`
may observe. The guide path's own five — transporter allocation, zone
contention, idle disposition, route selection, zone control — are the
passive subsystem's and are documented there.

---

## 6. Gotchas & best practices

### On a guide path, everything in the passive guide's §6 still applies

Under `AgvSystem` the space is the same space. A destination is still a resource, an idle
vehicle left on the guide path still blocks everything behind it, two-way
links are still where deadlock comes from, and zone size is still chosen
from control granularity rather than from how smooth the animation looks.
Read [`ksl-guidedpath` §6](ksl-guidedpath.md#6-gotchas--best-practices)
and treat it as part of this one. `GuidedPathDeadlockException` and the
obstruction count reach you unchanged.

The one thing that changes is that a vehicle now has a `DispositionPolicyIfc`
rather than the pool having an `IdleDispositionRuleIfc`, and it is per
vehicle rather than per fleet.

### What the dispatcher costs

More machinery than a pool's allocation rule, so the honest thing is to measure it rather than
assert it is cheap. `./gradlew :KSLExamples:agvBenchmark` runs the reference configuration — a
twenty-intersection, forty-link torus of 420 zones carrying twenty vehicles under saturated demand —
**both ways on one layout**, imported from the passive benchmark rather than restated so the two
cannot drift apart:

```
                                     active            passive
  zone traversals                 4,379,794          4,379,615
  events scheduled                4,412,310          4,412,312
  events / traversal                  1.007              1.007
  wall clock (s)                       5.06               4.77
  traversals / minute            51,964,070         55,041,395
  tasks completed                    55,564                 --

  JVM: OpenJDK 21.0.10, Linux amd64, 4 processors
```

Two things to read from it. The traversal counts agree to **0.004%**, which is the check that the
two subsystems are moving the same vehicles over the same aisles — if they diverged here, every
other comparison between the paradigms would be suspect. And **events per traversal is 1.007 in
both**: deciding costs nothing in engine events, because a dispatching pass is not a zone traversal.
The ~6% in wall clock is the dispatcher's and the vehicle agents' coroutines, which is what an
object that can hold an opinion costs.

Saturation is expressed differently on the two sides, necessarily. The passive benchmark re-dispatches
each vehicle the instant it arrives, which it can do because a transporter is a thing you command.
Here nobody commands a vehicle, so the load side saturates instead: forty loads that ask again on
arrival, against twenty vehicles, so the board is never empty.

### A staging area stages one vehicle

`MoveToStagingDisposition` names an intersection, and a zone holds one
vehicle. Send three vehicles to one staging intersection and one parks
there while the other two stop on the approach — which is usually not what
was wanted, and is exactly the configuration that quietly strangles a
model. Stage on a spur per vehicle, or accept that this is a rule about
one parking space.

The consequence is worth being concrete about, because it is
counter-intuitive: a vehicle stopped on the approach to a full staging
area is *still available*, and on a one-way link it cannot leave until
whatever is in the staging zone moves. A dispatcher may therefore commit a
task to a vehicle that cannot start on it. Measure `fracTimeBlocked` across
the fleet before believing a staging-area design.

### A vehicle under repair is an obstruction too

Everything the previous heading says about a flat vehicle applies to a
broken-down one for the length of its repair: it halts on the zones it
holds and closes every route through them. The difference is that a repair
ends, so this is a delay rather than a permanent hole — but a repair
distribution with a long tail on a one-way loop will produce blocking that
looks nothing like the mean.

`Cart2:NumTimesBlocked` on the *healthy* vehicles is where that shows up,
not on the one that failed.

### A flat vehicle is a closed aisle, not an idle asset

A vehicle that runs out of charge does not simply stop working. It halts on
the zones it holds and keeps holding them for the rest of the replication,
so every route through those zones is closed and the run's congestion
statistics describe a smaller network than the one you modelled. Nothing
raises: throughput falls and the report looks like a fleet that was merely
too small.

`Agv:NumVehiclesStranded` and `Cart:NumTimesStranded` are what say it
happened, and both are written for every replication, zero included. Any
value above zero means the run's other statistics were measured on a
layout that was missing some of its aisles.

The guard is `ChargeReservePolicy`, and it must reserve for **time as well
as distance**. Reaching a charger costs both, so a reserve computed from
distance alone under-reserves exactly when the trip is slow — which on a
guide path means exactly when it is congested. A reserve that was correct
for a model with no idle draw becomes incorrect the moment you add one,
and it fails in the direction that strands vehicles.

The reserve does not conjure capacity. It trades stranded vehicles for
unserved demand, which shows up as `Agv:NumTasksNeverAssigned`. That is the
honest outcome, and it is the one to act on.

### The re-tasking threshold is not optional

`ReassigningPolicy` requires a positive `improvementThreshold` and refuses
zero at construction. Watch `numAssignmentsRevoked`: a count that rises
with the run rather than settling is churn, and the usual cause is an
inner policy that ranks tasks instead of pairings (see §4).

### A bid cannot suspend, and that is load-bearing

`BidPolicyIfc.bid` is not a suspending function, which is what makes an
auction deadline of zero mean "everyone has bid" rather than "nobody had
time to". If you find yourself wanting to consume time inside a bid, the
thing you want is a longer deadline on the policy, not a suspending bid.

### There are two waiting lines, and they must not be summed

The dispatcher's `TaskQ` is the waiting line **for work**: a task sits in
it from posting until a vehicle takes possession, so its time in queue is
how long a load waited for a *decision* and the arrival that followed it.

A `Stop`'s queue is the waiting line **for a service**. A rider standing
at a stop is not waiting for a decision — there is no decision to wait
for. It is waiting for the next vehicle that comes past with room, which
is a function of headway and capacity.

Both are real, both are reported, and they answer different questions.
Adding them together produces a number that means nothing. A model that
uses only one of the two paradigms sees only one of the two rows.

### The dispatcher's queue is the waiting line; the hold queues are not

Six hold queues carry suspensions — awaiting pickup, awaiting boarding,
in transit, availability, dispatcher idle, out of service — and all six
report nothing by default.
`statisticalReportingForHoldQueues(true)` switches them on for debugging,
and reaches down to the [space layer's three](ksl-guidedpath.md#find-out-who-is-suspended-in-the-middle-of-a-journey)
as well, so a model being debugged shows all nine. Each `Stop` owns a
seventh kind, where a vehicle holding for a load waits; it is not switched
by that call and never reports, because a vehicle waiting at a stop is
already counted by the stop's own queue from the other side. Turn them off again:
they put rows on the report that look like waiting lines, and two of them
— riding, and the space layer's driving queue — are not. The queues to
read are `dispatcher.taskQ` and each `Stop`'s own.

Note which of the space layer's three the vehicles use. A vehicle agent
waits in `drivingHoldQ` for its own body, never in `ridingHoldQ`: under
this paradigm the load waits in *this* subsystem's `inTransitHoldQ` while
the vehicle drives. Passing the load rather than the agent as the waiter
would produce a model that runs and attributes the riding to the wrong
layer.

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| `FleetDispatchException` | A policy returned a vehicle not in `context.available`. Policies may only propose from what they were given. |
| `FleetAssignmentException` | A revocation after the load was aboard. Check `Assignment.isRevocable` before revoking. |
| `FleetInvariantViolation` | Not a modelling error. The subsystem's own bookkeeping disagrees with itself; the message says which record disagrees with which. |
| `numAssignmentsRevoked` climbs without bound | Re-tasking churn. Raise the threshold, or use an inner policy that ranks pairings. |
| `numTasksNeverAssigned` positive | The horizon ended with work outstanding. Expected in a terminating run; a warning sign in a steady-state one. |
| Loads wait, vehicles idle | A disposition sending vehicles somewhere they cannot get back from, or a policy excluding them as unreachable. Check `numVehiclesIdle` against `taskQ.numInQ`. |
| Run completes, most of the fleet motionless | The passive guide's §6. Check `numObstructionsDetected`. |
| Two runs with the same seed differ | A policy reading unmanaged state, or drawing randomness from a stream the model does not own. |

---

## 7. See also

- [`ksl-transport-tutorial`](ksl-transport-tutorial.md) — every example in
  this guide worked through: problem, model, result, and what it shows.

- [`ksl-transport`](ksl-transport.md) — the overview: which of the four
  transport subsystems to use, and the measurement that separates them.
- [`ksl-guidedpath`](ksl-guidedpath.md) — **read this first if your
  vehicles contend for space.** The physical layer `AgvSystem` runs on:
  networks, zones, links, blocking, routing, zone control, and deadlock.
  Everything there is true of the guide-path binding.
- [`ksl-agent`](ksl-agent.md) — the agent framework the vehicles and the
  dispatcher are built on: mailboxes, `contractNet`, runtime agents.
- [`ksl-entity`](ksl-entity.md) — the process view, and where
  `transportByFleet` sits among `KSLProcessBuilder`'s other verbs.
- [`ksl-spatial`](ksl-spatial.md) — `MovableResource`, `DistancesModel`
  and the movement seam, for vehicles that do not contend for space at
  all. This is the substrate `FreePathFleet` binds to.
- `KSLExamples`, under `ksl.examples.general.agv`:
  - `TwoParadigmsExample` — the same shop modelled both ways, agreeing
    exactly on one vehicle. **Read this one first.**
  - `DispatchingRuleComparison` — six rules on common random numbers.
  - `RetaskingInFlightExample` — a cart turned round, with the arithmetic
    made unambiguous.
  - `MultiFloorHospitalExample` — two floors joined by lifts, with no lift
    class anywhere in it.
- `KSLExamples`, under `ksl.examples.general.fleet`:
  - `FreePathFleetExample` — the same dispatcher over a plane, at two
    capacities and two batching windows. **Read this one for the free-path
    binding.**
